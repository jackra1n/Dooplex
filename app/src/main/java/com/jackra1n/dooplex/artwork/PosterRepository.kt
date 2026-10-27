package com.jackra1n.dooplex.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexConnection
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Newest demand is served first, so the posters on screen win over ones scrolled past.
 * Queued requests disappear with their last consumer; started ones finish and fill the caches.
 */
class PosterRepository(context: Context, private val connection: PlexConnection) : Closeable {
    private val client = PlexClient(connection)
    private val disk = PosterDiskCache.shared(File(context.applicationContext.cacheDir, "posters-v1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val memory = LinkedHashMap<Key, MemoryEntry>(64, 0.75f, true)
    private val requests = HashMap<Key, Request>()
    private val queue = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private val idle = MutableStateFlow(true)
    private val decodeSlot = Semaphore(1)
    private var memoryBytes = 0L
    private var closed = false

    private val diskScope by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf(connection.serverUrl, connection.token, connection.clientId)) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        digest.digest()
    }

    init {
        repeat(WORKERS) { scope.launch { work() } }
    }

    /** A cheap, memory-only lookup; no URL construction, SHA, filesystem access or decoding. */
    fun cached(artworkPath: String, width: Int, height: Int): Bitmap? = synchronized(lock) {
        if (closed) null else memory[Key(artworkPath, width, height)]?.bitmap
    }

    internal fun cachedAtmosphere(artworkPath: String, width: Int, height: Int): MovieAtmosphere? =
        synchronized(lock) {
            if (closed) null else memory[Key(artworkPath, width, height)]?.atmosphere
        }

    /** Lookahead yields to foreground requests before starting its next speculative image. */
    internal suspend fun awaitIdle() {
        idle.first { it }
    }

    suspend fun load(artworkPath: String, width: Int, height: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw ArtworkException("Choose valid artwork dimensions.")
        }
        val key = Key(artworkPath, width, height)
        val request = synchronized(lock) {
            if (closed) throw ArtworkException("The artwork repository is closed.")
            memory[key]?.let {
                Analytics.sample("poster.memory", 1.0)
                return it.bitmap
            }
            val request = requests[key] ?: Request(key).also {
                requests[key] = it
                idle.value = false
                wake.trySend(Unit)
            }
            if (!request.started) {
                queue.remove(request)
                queue.addLast(request)
            }
            request.consumers++
            request
        }
        try {
            return request.result.await()
        } finally {
            synchronized(lock) {
                request.consumers--
                if (request.consumers == 0 && !request.started && requests[key] === request) {
                    queue.remove(request)
                    requests.remove(key)
                    idle.value = requests.isEmpty()
                }
            }
        }
    }

    override fun close() {
        val abandoned = synchronized(lock) {
            if (closed) return
            closed = true
            queue.clear()
            memory.clear()
            memoryBytes = 0L
            idle.value = true
            requests.values.toList().also { requests.clear() }
        }
        abandoned.forEach { it.result.completeExceptionally(ArtworkException("The artwork repository is closed.")) }
        wake.close()
        // Bitmaps are never recycled on eviction/close because a composed painter may still hold them.
        scope.cancel()
    }

    private suspend fun work() {
        while (wake.receiveCatching().isSuccess) {
            val request = synchronized(lock) {
                queue.removeLastOrNull()?.also { it.started = true }
            } ?: continue
            try {
                fetch(request)
            } catch (cancelled: CancellationException) {
                request.result.completeExceptionally(ArtworkException("The artwork request was cancelled."))
                throw cancelled
            } catch (failure: ArtworkException) {
                request.result.completeExceptionally(failure)
            } catch (_: IOException) {
                request.result.completeExceptionally(
                    ArtworkException("Artwork could not be loaded; check the Plex server connection."),
                )
            } catch (_: RuntimeException) {
                request.result.completeExceptionally(ArtworkException("Artwork could not be loaded."))
            } finally {
                synchronized(lock) {
                    if (requests[request.key] === request) requests.remove(request.key)
                    if (!closed) idle.value = requests.isEmpty()
                }
            }
        }
    }

    private suspend fun fetch(request: Request) {
        val key = request.key
        val context = currentCoroutineContext()
        val name = diskName(key)
        val started = System.nanoTime()
        disk.read(name, context)?.let { image ->
            val bitmap = try {
                decode(image, key)
            } catch (_: ArtworkException) {
                disk.remove(name)
                null
            }
            if (bitmap != null) {
                request.result.complete(publish(key, bitmap))
                Analytics.sample("poster.disk.ms", (System.nanoTime() - started) / 1e6)
                return
            }
        }
        val image = download(client.artworkUrl(key.artworkPath, key.width, key.height), context)
        request.result.complete(publish(key, decode(image, key)))
        Analytics.sample("poster.network.ms", (System.nanoTime() - started) / 1e6)
        disk.write(name, image, context)
    }

    private suspend fun publish(key: Key, decoded: Bitmap): Bitmap {
        // Sampling finishes off-main before publication, so a displayed poster's palette
        // is already available to synchronous focus updates without another request.
        val atmosphere = extractMovieAtmosphere(decoded)
        val bitmap = toGpu(decoded)
        val entry = MemoryEntry(bitmap, bitmap.width.toLong() * bitmap.height * 4, atmosphere)
        synchronized(lock) {
            if (closed || entry.bytes > MEMORY_BUDGET_BYTES) return bitmap
            memory.remove(key)?.let { memoryBytes -= it.bytes }
            memory[key] = entry
            memoryBytes += entry.bytes
            val iterator = memory.entries.iterator()
            while (memoryBytes > MEMORY_BUDGET_BYTES && iterator.hasNext()) {
                memoryBytes -= iterator.next().value.bytes
                iterator.remove()
            }
        }
        return bitmap
    }

    /** Drops decoded posters, so a benchmark can start cold. */
    fun clearMemory() = synchronized(lock) {
        memory.clear()
        memoryBytes = 0L
    }

    fun clearDisk() = disk.clear()

    // The render thread uploads the texture between frames. A hardware bitmap would instead be
    // imported by the GPU inside the first frame that draws it, which stalls that frame on this box.
    private fun toGpu(decoded: Bitmap): Bitmap {
        decoded.prepareToDraw()
        return decoded
    }

    private fun download(url: String, context: CoroutineContext): ImageBytes {
        val http = URI(url).toURL().openConnection() as HttpURLConnection
        var complete = false
        try {
            http.requestMethod = "GET"
            http.connectTimeout = SOCKET_TIMEOUT_MS
            http.readTimeout = SOCKET_TIMEOUT_MS
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.setRequestProperty("Accept", "image/*")
            http.setRequestProperty("Accept-Encoding", "identity")
            http.setRequestProperty("X-Plex-Token", connection.token)
            http.setRequestProperty("X-Plex-Client-Identifier", connection.clientId)
            val status = http.responseCode
            if (status !in 200..299) throw ArtworkException("Plex artwork request failed (HTTP $status).")
            val length = http.contentLengthLong
            if (length > MAX_IMAGE_BYTES) throw ArtworkException("Plex artwork exceeded the size limit.")
            val image = http.inputStream.use { readImage(it, initialCapacity(length), context, REQUEST_TIMEOUT_NS) }
            complete = true
            return image
        } finally {
            // A fully read response returns its socket to the keep-alive pool; anything else drops it.
            if (!complete) disconnectQuietly(http)
        }
    }

    private suspend fun decode(image: ImageBytes, key: Key): Bitmap = decodeSlot.withPermit {
        currentCoroutineContext().ensureActive()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.bytes, 0, image.size(), bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth !in 1..MAX_SOURCE_DIMENSION || sourceHeight !in 1..MAX_SOURCE_DIMENSION ||
                sourceWidth.toLong() * sourceHeight > MAX_SOURCE_PIXELS
            ) {
                throw ArtworkException("Plex returned unsupported artwork dimensions.")
            }
            var sample = 1
            while (sourceWidth.toLong() >= key.width.toLong() * sample * 2 ||
                sourceHeight.toLong() >= key.height.toLong() * sample * 2
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.size(), options)
                ?: throw ArtworkException("Plex returned artwork that could not be decoded.")
            val scale = min(1.0, min(key.width.toDouble() / decoded.width, key.height.toDouble() / decoded.height))
            if (scale >= 1.0) return@withPermit decoded
            val width = (decoded.width * scale).toInt().coerceIn(1, key.width)
            val height = (decoded.height * scale).toInt().coerceIn(1, key.height)
            try {
                Bitmap.createScaledBitmap(decoded, width, height, true)
            } finally {
                decoded.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            throw ArtworkException("Artwork could not fit in available memory.")
        } catch (failure: ArtworkException) {
            throw failure
        } catch (_: RuntimeException) {
            throw ArtworkException("Plex returned artwork that could not be decoded.")
        }
    }

    private fun diskName(key: Key): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(diskScope)
        for (value in listOf(key.artworkPath, key.width.toString(), key.height.toString())) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 15]
        }
        return String(hex) + ".poster"
    }

    private data class Key(val artworkPath: String, val width: Int, val height: Int)
    private data class MemoryEntry(val bitmap: Bitmap, val bytes: Long, val atmosphere: MovieAtmosphere)

    private class Request(val key: Key) {
        val result = CompletableDeferred<Bitmap>()
        var consumers = 0
        var started = false
    }

    private companion object {
        const val WORKERS = 4
        const val MEMORY_BUDGET_BYTES = 96L * 1024 * 1024
        const val MAX_DIMENSION = 2_048
        const val MAX_SOURCE_DIMENSION = 32_768
        const val MAX_SOURCE_PIXELS = 64L * 1024 * 1024
        const val SOCKET_TIMEOUT_MS = 8_000
        const val REQUEST_TIMEOUT_NS = 16_000_000_000L
        const val HEX = "0123456789abcdef"

        fun disconnectQuietly(http: HttpURLConnection) {
            try {
                http.disconnect()
            } catch (_: RuntimeException) {
                // Cleanup must not replace the sanitized request outcome.
            }
        }
    }
}

/**
 * Process-wide LRU index over the poster files, built by one directory scan on first use.
 * Reads and writes then touch only their own file instead of rescanning the directory.
 */
private class PosterDiskCache(private val root: File) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Long>(256, 0.75f, true)
    private var bytes = 0L
    private var ready = false
    private var available = false

    fun read(name: String, context: CoroutineContext): ImageBytes? {
        val length = synchronized(lock) {
            if (!open()) return null
            val length = entries[name] ?: return null
            if (length !in 1..MAX_IMAGE_BYTES.toLong()) {
                forget(name)
                return null
            }
            length
        }
        val file = File(root, name)
        return try {
            val image = FileInputStream(file).use { readImage(it, initialCapacity(length), context) }
            file.setLastModified(System.currentTimeMillis())
            image
        } catch (_: IOException) {
            remove(name)
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun remove(name: String) = synchronized(lock) {
        if (open()) forget(name)
    }

    fun clear() = synchronized(lock) {
        if (open()) entries.keys.toList().forEach(::forget)
    }

    fun write(name: String, image: ImageBytes, context: CoroutineContext) {
        if (!synchronized(lock) { open() }) return
        var temporary: File? = null
        try {
            context.ensureActive()
            temporary = File.createTempFile("write-", ".tmp", root)
            FileOutputStream(temporary).use { image.writeTo(it) }
            Files.move(
                temporary.toPath(), File(root, name).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            synchronized(lock) {
                entries.put(name, image.size().toLong())?.let { bytes -= it }
                bytes += image.size()
                trim()
            }
        } catch (_: IOException) {
            // Disk pressure is not an artwork failure; the decoded image stays usable.
        } catch (_: SecurityException) {
            // The in-memory cache also works when app cache storage is unavailable.
        } catch (_: UnsupportedOperationException) {
            // Do not fall back to a non-atomic cache write on an unsupported filesystem.
        } finally {
            try {
                temporary?.delete()
            } catch (_: SecurityException) {
                // An abandoned temporary file is reclaimed by the next startup scan.
            }
        }
    }

    private fun open(): Boolean {
        if (ready) return available
        ready = true
        try {
            if (!root.isDirectory && !root.mkdirs()) return false
            val files = root.listFiles() ?: return false
            files.filter { it.isFile }.sortedBy { it.lastModified() }.forEach { file ->
                if (file.name.endsWith(".tmp")) file.delete() else {
                    entries[file.name] = file.length()
                    bytes += file.length()
                }
            }
            available = true
            trim()
        } catch (_: SecurityException) {
            available = false
        }
        return available
    }

    private fun forget(name: String) {
        entries.remove(name)?.let { bytes -= it }
        try {
            File(root, name).delete()
        } catch (_: SecurityException) {
            // The optional cache must never prevent a fresh network request.
        }
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while ((bytes > DISK_BUDGET_BYTES || entries.size > MAX_DISK_ENTRIES) && iterator.hasNext()) {
            val (name, length) = iterator.next()
            iterator.remove()
            bytes -= length
            try {
                File(root, name).delete()
            } catch (_: SecurityException) {
                // A file that cannot be deleted is simply no longer indexed.
            }
        }
    }

    companion object {
        private const val DISK_BUDGET_BYTES = 256L * 1024 * 1024
        private const val MAX_DISK_ENTRIES = 8_192
        private var instance: PosterDiskCache? = null

        @Synchronized
        fun shared(root: File): PosterDiskCache = instance ?: PosterDiskCache(root).also { instance = it }
    }
}

private class ArtworkException(message: String) : IOException(message)

// Exposing the owned buffer avoids a second full compressed-image copy for decoding/writing.
private class ImageBytes(capacity: Int) : ByteArrayOutputStream(capacity) {
    val bytes: ByteArray get() = buf
}

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

private fun initialCapacity(length: Long): Int =
    if (length in 1..MAX_IMAGE_BYTES.toLong()) length.toInt() else 32 * 1024

private fun readImage(
    input: InputStream,
    initialCapacity: Int,
    context: CoroutineContext,
    timeoutNs: Long = Long.MAX_VALUE,
): ImageBytes {
    val started = System.nanoTime()
    val result = ImageBytes(initialCapacity)
    val buffer = ByteArray(8_192)
    while (true) {
        context.ensureActive()
        if (System.nanoTime() - started > timeoutNs) throw ArtworkException("The artwork request timed out.")
        val count = input.read(buffer)
        if (count < 0) break
        if (result.size() + count > MAX_IMAGE_BYTES) {
            throw ArtworkException("Plex artwork exceeded the size limit.")
        }
        result.write(buffer, 0, count)
    }
    return result
}

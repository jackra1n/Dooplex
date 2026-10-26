package com.jackra1n.dooplex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

class PosterRepository(context: Context, private val connection: PlexConnection) : Closeable {
    private val appContext = context.applicationContext
    private val client = PlexClient(connection)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val memory = LinkedHashMap<Key, MemoryEntry>(32, 0.75f, true)
    private val requests = HashMap<Key, Request>()
    private val idle = MutableStateFlow(true)
    private var memoryBytes = 0L
    private var closed = false

    // A slot covers the entire operation, so cancelled socket workers retain their slot
    // until they have actually stopped. Queued requests disappear with their last consumer.
    private val workSlots = Semaphore(4)
    private val decodeSlot = Semaphore(1)

    // These are first accessed only by IO-dispatched request jobs, never by cached()/close().
    private val diskDirectory by lazy { File(appContext.cacheDir, "posters-v1") }
    private val diskScope by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf(connection.serverUrl, connection.token, connection.clientId)) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        digest.digest()
    }
    private var diskPrepared = false // guarded by diskLock across repository instances

    /** A cheap, memory-only lookup; no URL construction, SHA, filesystem access or decoding. */
    fun cached(artworkPath: String, width: Int, height: Int): Bitmap? = synchronized(lock) {
        if (closed) null else memory[Key(artworkPath, width, height)]?.bitmap
    }

    /** Published atomically with its bitmap and evicted with the same bounded memory entry. */
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
            memory[key]?.let { return it.bitmap }
            val shared = requests[key] ?: run {
                val created = Request(scope.async(start = CoroutineStart.LAZY) { fetch(key) })
                requests[key] = created
                idle.value = false
                created.result.invokeOnCompletion {
                    synchronized(lock) {
                        if (requests[key] === created) {
                            requests.remove(key)
                            idle.value = requests.isEmpty()
                        }
                    }
                }
                created
            }
            shared.consumers += 1
            shared
        }
        try {
            return request.result.await()
        } finally {
            val cancel = synchronized(lock) {
                request.consumers -= 1
                if (request.consumers == 0 && !request.result.isCompleted) {
                    if (requests[key] === request) {
                        requests.remove(key)
                        idle.value = requests.isEmpty()
                    }
                    true
                } else {
                    false
                }
            }
            // This shared Deferred is not a child of any caller. One caller's cancellation
            // cannot cancel another caller; the final departure removes it before cancelling.
            if (cancel) request.result.cancel()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            requests.clear()
            idle.value = true
            memory.clear()
            memoryBytes = 0L
        }
        // Cancellation hooks dispatch socket cleanup off-main; bitmaps are never recycled
        // on eviction/close because a composed painter may still hold them.
        scope.cancel()
    }

    private suspend fun fetch(key: Key): Bitmap = workSlots.withPermit {
        try {
            currentCoroutineContext().ensureActive()
            synchronized(lock) { memory[key]?.let { return@withPermit it.bitmap } }
            val name = diskName(key)
            val fromDisk = readDisk(name)
            if (fromDisk != null) {
                val bitmap = try {
                    decode(fromDisk, key)
                } catch (_: ArtworkException) {
                    removeDisk(name)
                    null
                }
                if (bitmap != null) {
                    remember(key, bitmap)
                    return@withPermit bitmap
                }
            }
            val url = client.artworkUrl(key.artworkPath, key.width, key.height)
            val image = download(url)
            val bitmap = decode(image, key)
            writeDisk(name, image)
            remember(key, bitmap)
            bitmap
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ArtworkException) {
            throw failure
        } catch (_: IOException) {
            throw ArtworkException("Artwork could not be loaded; check the Plex server connection.")
        } catch (_: RuntimeException) {
            // Neither platform errors nor URL/helper exception causes leave this boundary.
            throw ArtworkException("Artwork could not be loaded.")
        }
    }

    private suspend fun remember(key: Key, bitmap: Bitmap) {
        currentCoroutineContext().ensureActive()
        // Sampling finishes off-main before publication, so a displayed poster's palette
        // is already available to synchronous focus updates without another request.
        val entry = MemoryEntry(bitmap, bitmap.allocationByteCount.toLong(), extractMovieAtmosphere(bitmap))
        currentCoroutineContext().ensureActive()
        bitmap.prepareToDraw()
        synchronized(lock) {
            if (closed || entry.bytes > MEMORY_BUDGET_BYTES) return
            memory.remove(key)?.let { memoryBytes -= it.bytes }
            memory[key] = entry
            memoryBytes += entry.bytes
            val iterator = memory.entries.iterator()
            while (memoryBytes > MEMORY_BUDGET_BYTES && iterator.hasNext()) {
                memoryBytes -= iterator.next().value.bytes
                iterator.remove()
            }
        }
    }

    private suspend fun download(url: String): ImageBytes {
        try {
            return withTimeout(REQUEST_TIMEOUT_MS) {
                coroutineScope {
                    val activeConnection = AtomicReference<HttpURLConnection?>()
                    suspendCancellableCoroutine { continuation ->
                        val worker = launch(Dispatchers.IO) {
                            try {
                                val context = currentCoroutineContext()
                                context.ensureActive()
                                val http = URI(url).toURL().openConnection() as HttpURLConnection
                                activeConnection.set(http)
                                context.ensureActive()
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
                                if (status !in 200..299) {
                                    throw ArtworkException("Plex artwork request failed (HTTP $status).")
                                }
                                val length = http.contentLengthLong
                                if (length > MAX_IMAGE_BYTES) {
                                    throw ArtworkException("Plex artwork exceeded the size limit.")
                                }
                                val image = http.inputStream.use { input ->
                                    readImage(input, initialCapacity(length), context)
                                }
                                context.ensureActive()
                                continuation.resume(image)
                            } catch (cancelled: CancellationException) {
                                continuation.cancel(cancelled)
                            } catch (failure: ArtworkException) {
                                continuation.resumeWithException(failure)
                            } catch (_: IOException) {
                                continuation.resumeWithException(
                                    ArtworkException("Artwork request could not be completed; check the Plex server connection."),
                                )
                            } catch (_: RuntimeException) {
                                continuation.resumeWithException(ArtworkException("Artwork request could not be completed."))
                            } finally {
                                activeConnection.getAndSet(null)?.let(::disconnectQuietly)
                            }
                        }
                        continuation.invokeOnCancellation {
                            worker.cancel()
                            activeConnection.get()?.let { http ->
                                // HttpURLConnection reads are blocking. Disconnect promptly without
                                // doing potentially blocking socket cleanup on a cancelling UI thread.
                                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { disconnectQuietly(http) })
                            }
                        }
                    }
                    // coroutineScope waits for the socket worker's finally before releasing its slot.
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ArtworkException("The artwork request timed out.")
        }
    }

    private fun readImage(input: InputStream, initialCapacity: Int, context: CoroutineContext): ImageBytes {
        val result = ImageBytes(initialCapacity)
        val buffer = ByteArray(8_192)
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > MAX_IMAGE_BYTES) {
                throw ArtworkException("Plex artwork exceeded the size limit.")
            }
            result.write(buffer, 0, count)
        }
        return result
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
            currentCoroutineContext().ensureActive()
            val scale = min(1.0, min(key.width.toDouble() / decoded.width, key.height.toDouble() / decoded.height))
            if (scale >= 1.0) return@withPermit decoded
            val width = (decoded.width * scale).toInt().coerceIn(1, key.width)
            val height = (decoded.height * scale).toInt().coerceIn(1, key.height)
            try {
                Bitmap.createScaledBitmap(decoded, width, height, true)
            } finally {
                // Only this private, unpublished downsampling intermediate is recycled.
                decoded.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            throw ArtworkException("Artwork could not fit in available memory.")
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

    private suspend fun readDisk(name: String): ImageBytes? = diskLock.withLock {
        try {
            val directory = prepareDisk() ?: return@withLock null
            val file = File(directory, name)
            if (!file.isFile) return@withLock null
            val length = file.length()
            if (length !in 1..MAX_IMAGE_BYTES.toLong()) {
                file.delete()
                return@withLock null
            }
            val context = currentCoroutineContext()
            val image = FileInputStream(file).use { readImage(it, initialCapacity(length), context) }
            file.setLastModified(System.currentTimeMillis())
            image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun removeDisk(name: String) = diskLock.withLock {
        try {
            File(diskDirectory, name).delete()
        } catch (_: SecurityException) {
            // The optional cache must never prevent a fresh network request.
        }
    }

    private suspend fun writeDisk(name: String, image: ImageBytes) = diskLock.withLock {
        var temporary: File? = null
        try {
            currentCoroutineContext().ensureActive()
            val directory = prepareDisk() ?: return@withLock
            // Reserve the temporary file too, not merely the eventual renamed entry.
            if (!pruneDisk(directory, image.size().toLong(), 1)) return@withLock
            temporary = File.createTempFile("write-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                image.writeTo(output)
            }
            currentCoroutineContext().ensureActive()
            Files.move(
                temporary.toPath(), File(directory, name).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // Disk pressure/eviction is not an artwork failure; the decoded image stays usable.
        } catch (_: SecurityException) {
            // The in-memory cache also works when app cache storage is unavailable.
        } catch (_: UnsupportedOperationException) {
            // Do not fall back to a non-atomic cache write on an unsupported filesystem.
        } finally {
            try {
                temporary?.delete()
            } catch (_: SecurityException) {
                // An abandoned temporary file is reclaimed by the next prune.
            }
        }
    }

    private suspend fun prepareDisk(): File? {
        val directory = diskDirectory
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (!diskPrepared) {
            if (!pruneDisk(directory, 0, 0)) return null
            diskPrepared = true
        }
        return directory
    }

    private suspend fun pruneDisk(directory: File, reservedBytes: Long, reservedEntries: Int): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val files = directory.listFiles() ?: return false
        val entries = ArrayList<DiskEntry>(files.size)
        var bytes = 0L
        for (file in files) {
            context.ensureActive()
            if (!file.isFile) continue
            if (file.name.endsWith(".tmp") && file.delete()) continue
            val entry = DiskEntry(file, file.length(), file.lastModified())
            entries.add(entry)
            bytes += entry.bytes
        }
        var count = entries.size
        if (bytes + reservedBytes <= DISK_BUDGET_BYTES && count + reservedEntries <= MAX_DISK_ENTRIES) return true
        entries.sortBy { it.modified }
        for (entry in entries) {
            context.ensureActive()
            if (entry.file.delete()) {
                bytes -= entry.bytes
                count -= 1
            }
            if (bytes + reservedBytes <= DISK_BUDGET_BYTES && count + reservedEntries <= MAX_DISK_ENTRIES) return true
        }
        return false
    }

    private data class Key(val artworkPath: String, val width: Int, val height: Int)
    private data class MemoryEntry(val bitmap: Bitmap, val bytes: Long, val atmosphere: MovieAtmosphere)
    private class Request(val result: Deferred<Bitmap>, var consumers: Int = 0)
    private data class DiskEntry(val file: File, val bytes: Long, val modified: Long)
    private class ArtworkException(message: String) : IOException(message)

    // Exposing the owned buffer avoids a second full compressed-image copy for decoding/writing.
    private class ImageBytes(capacity: Int) : ByteArrayOutputStream(capacity) {
        val bytes: ByteArray get() = buf
    }

    private companion object {
        const val MEMORY_BUDGET_BYTES = 48L * 1024 * 1024
        const val DISK_BUDGET_BYTES = 128L * 1024 * 1024
        const val MAX_DISK_ENTRIES = 2_048
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_DIMENSION = 2_048
        const val MAX_SOURCE_DIMENSION = 32_768
        const val MAX_SOURCE_PIXELS = 64L * 1024 * 1024
        const val SOCKET_TIMEOUT_MS = 8_000
        const val REQUEST_TIMEOUT_MS = 16_000L
        const val HEX = "0123456789abcdef"
        val diskLock = Mutex()

        fun initialCapacity(length: Long): Int =
            if (length in 1..MAX_IMAGE_BYTES.toLong()) length.toInt() else 32 * 1024

        fun disconnectQuietly(http: HttpURLConnection) {
            try {
                http.disconnect()
            } catch (_: RuntimeException) {
                // Cleanup must not replace the sanitized request outcome.
            }
        }
    }
}

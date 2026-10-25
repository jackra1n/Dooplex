package com.jackra1n.dooplex

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class PlexClient(private val connection: PlexConnection) {
    private val baseUrl = validateServerUrl(connection.serverUrl)
    private val identity: Map<String, String>

    init {
        require(isHeaderValue(connection.token)) { "A valid Plex token is required." }
        require(isHeaderValue(connection.clientId)) { "A stable Plex client identifier is required." }
        identity = linkedMapOf(
            "X-Plex-Client-Identifier" to connection.clientId,
            "X-Plex-Product" to "Dooplex",
            "X-Plex-Version" to "1.0",
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to "Zidoo",
            "X-Plex-Device-Name" to "Dooplex on Zidoo",
            "X-Plex-Provides" to "player",
        )
    }

    suspend fun libraries(): List<PlexLibrary> = withContext(Dispatchers.IO) {
        try {
            val directories = JSONObject(request("/library/sections", operation = "libraries"))
                .getJSONObject("MediaContainer").optJSONArray("Directory")
            buildList {
                if (directories != null) for (index in 0 until directories.length()) {
                    val item = directories.getJSONObject(index)
                    val key = item.optString("key")
                    val type = item.optString("type")
                    if (RATING_KEY.matches(key) && type in setOf("movie", "show")) {
                        add(PlexLibrary(key, item.optString("title", "Library"), type))
                    }
                }
            }
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid library information.")
        }
    }

    suspend fun browse(path: String, start: Int = 0): PlexBrowsePage = withContext(Dispatchers.IO) {
        require(Regex("/library/(?:sections/[0-9]+/all|metadata/[0-9]+/children)").matches(path)) {
            "A valid Plex library or children path is required."
        }
        require(start >= 0) { "A nonnegative Plex page offset is required." }
        try {
            val container = JSONObject(request(
                path,
                mapOf("X-Plex-Container-Start" to start.toString(), "X-Plex-Container-Size" to "40"),
                operation = "browse",
            )).getJSONObject("MediaContainer")
            val metadata = container.optJSONArray("Metadata")
            val items = buildList {
                if (metadata != null) for (index in 0 until metadata.length()) {
                    val item = metadata.getJSONObject(index)
                    val key = item.optString("ratingKey")
                    val type = item.optString("type")
                    if (!RATING_KEY.matches(key) || type !in setOf("movie", "show", "season", "episode")) continue
                    val details = buildList {
                        if (item.optInt("year") > 0) add(item.optInt("year").toString())
                        if (type == "episode") {
                            add("S${item.optInt("parentIndex")} · E${item.optInt("index")}")
                            item.optString("grandparentTitle").takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        if (type == "season") add("${item.optInt("leafCount")} episodes")
                    }.joinToString(" · ")
                    add(PlexBrowseItem(key, item.optString("title", "Untitled"), details,
                        if (type == "show" || type == "season") "/library/metadata/$key/children" else null))
                }
            }
            val offset = container.optInt("offset", start)
            val total = container.optInt("totalSize", container.optInt("size", metadata?.length() ?: 0))
            if (offset < 0 || total < 0) throw IOException("Plex returned invalid page information.")
            PlexBrowsePage(items, offset, total)
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid title information.")
        }
    }

    suspend fun loadMedia(ratingKey: String): PlaybackMedia = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        requireRatingKey(ratingKey)
        val body = request("/library/metadata/$ratingKey", operation = "metadata")
        val metadata = try {
            val items = JSONObject(body).getJSONObject("MediaContainer").optJSONArray("Metadata")
                ?: throw IOException("Plex returned no media metadata.")
            if (items.length() != 1) throw IOException("Plex did not return one media item.")
            items.getJSONObject(0)
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid media metadata.")
        }
        if (metadata.optString("ratingKey") != ratingKey) {
            throw IOException("Plex returned a different media item.")
        }
        val type = metadata.optString("type")
        if (type != "movie" && type != "episode") {
            throw IOException("Choose a Plex movie or episode, not a collection, show, or season.")
        }
        val versions = metadata.optJSONArray("Media")
            ?: throw IOException("This Plex item has no original media file.")
        var hasMultipart = false
        for (mediaIndex in 0 until versions.length()) {
            currentCoroutineContext().ensureActive()
            val version = versions.optJSONObject(mediaIndex) ?: continue
            // Optimized/proxy versions are not the original file requested by this diagnostic.
            if (version.optInt("proxyType", 0) != 0 || version.isTrue("indirect")) continue
            val parts = version.optJSONArray("Part") ?: continue
            if (parts.length() > 1) {
                hasMultipart = true
                continue
            }
            if (parts.length() != 1) continue
            val part = parts.optJSONObject(0) ?: continue
            if (part.isFalse("exists") || part.isFalse("accessible")) continue
            val partKey = part.optString("key")
            if (partKey.isEmpty()) continue
            requirePartKey(partKey)
            var duration = version.optLong("duration", 0)
            if (duration <= 0) duration = metadata.optLong("duration", 0)
            if (duration <= 0) duration = part.optLong("duration", 0)
            if (duration <= 0) throw IOException("Plex did not provide a usable media duration.")
            val streamUrl = url(partKey, identity + ("X-Plex-Token" to connection.token))
            currentCoroutineContext().ensureActive()
            return@withContext PlaybackMedia(
                ratingKey = ratingKey,
                title = metadata.optString("title").ifBlank { "Plex $type" },
                durationMs = duration,
                resumePositionMs = metadata.optLong("viewOffset", 0).coerceIn(0, duration),
                streamUrl = streamUrl,
                partKey = partKey,
                mediaIndex = mediaIndex,
                partIndex = 0,
            )
        }
        if (hasMultipart) {
            throw IOException("Multipart original media is unsupported; choose a single-file version.")
        }
        throw IOException("This Plex item has no accessible single-file original media.")
    }

    /** Call on actual state changes and at least every ten seconds while playback is observed. */
    suspend fun timeline(
        media: PlaybackMedia,
        state: String,
        positionMs: Long,
        sessionId: String,
    ): Unit = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        requireRatingKey(media.ratingKey)
        requirePartKey(media.partKey)
        require(state in PLAYBACK_STATES) {
            "Plex requires an observed buffering, playing, paused, or stopped state."
        }
        require(positionMs >= 0 && media.durationMs > 0) { "Plex requires a valid playback position and duration." }
        require(media.mediaIndex >= 0 && media.partIndex == 0) { "Unsupported Plex media or part index." }
        require(isHeaderValue(sessionId)) { "A stable playback session identifier is required." }
        val parameters = linkedMapOf(
            "identifier" to "com.plexapp.plugins.library",
            "key" to "/library/metadata/${media.ratingKey}",
            "ratingKey" to media.ratingKey,
            "state" to state,
            // PMS rejects time beyond duration, including small player rounding overruns.
            "time" to positionMs.coerceAtMost(media.durationMs).toString(),
            "duration" to media.durationMs.toString(),
            "type" to "video",
            "mediaIndex" to media.mediaIndex.toString(),
            "partIndex" to media.partIndex.toString(),
            "partCount" to "1",
            "partID" to media.partKey.split('/')[3],
            "playbackSessionID" to sessionId,
            "directPlay" to "1",
            "directStream" to "0",
            "protocol" to URI(baseUrl).scheme,
            "offline" to "0",
        )
        // The documented session header disambiguates playback sessions for a stable client.
        // Media/part and direct-play hints supplement it, but do not guarantee dashboard fields.
        request("/:/timeline", parameters, sessionId, "timeline")
        currentCoroutineContext().ensureActive()
    }

    private suspend fun request(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        sessionId: String? = null,
        operation: String,
    ): String {
        currentCoroutineContext().ensureActive()
        var http: HttpURLConnection? = null
        try {
            val client = URI(url(path, parameters)).toURL().openConnection() as HttpURLConnection
            http = client
            client.requestMethod = "GET"
            // Never forward credentials to a redirect target, even on a successful HTTP response.
            client.instanceFollowRedirects = false
            client.connectTimeout = 8_000
            client.readTimeout = 8_000
            client.useCaches = false
            client.setRequestProperty("Accept", "application/json")
            client.setRequestProperty("X-Plex-Token", connection.token)
            identity.forEach { (name, value) -> client.setRequestProperty(name, value) }
            if (sessionId != null) client.setRequestProperty("X-Plex-Session-Identifier", sessionId)
            val status = client.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) {
                throw PlexRequestException("Plex $operation request failed (HTTP $status).")
            }
            val output = ByteArrayOutputStream()
            client.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw PlexRequestException("Plex $operation response was too large.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            return output.toString(Charsets.UTF_8.name())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PlexRequestException) {
            throw failure
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            // Original exception messages/causes may contain credential-bearing URLs.
            throw IOException("Plex $operation request could not be completed; check the server connection.")
        } finally {
            http?.disconnect()
        }
    }

    private fun url(path: String, parameters: Map<String, String>): String {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return baseUrl + path + if (query.isEmpty()) "" else "?$query"
    }

    private fun requireRatingKey(ratingKey: String) {
        require(RATING_KEY.matches(ratingKey)) { "Enter the numeric Plex rating key of a movie or episode." }
    }

    private fun requirePartKey(partKey: String) {
        // PMS original-file keys only. Absolute URLs, queries, traversal, SMB and arbitrary
        // endpoints must not receive the token when handed to an external player.
        if (!PART_KEY.matches(partKey)) {
            throw IOException("Plex returned an unsupported or unsafe original-media part key.")
        }
    }

    private fun JSONObject.isTrue(name: String): Boolean =
        opt(name)?.toString().let { it == "1" || it.equals("true", ignoreCase = true) }

    private fun JSONObject.isFalse(name: String): Boolean =
        opt(name)?.toString().let { it == "0" || it.equals("false", ignoreCase = true) }

    private class PlexRequestException(message: String) : IOException(message)

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        val RATING_KEY = Regex("[0-9]+")
        val PART_KEY = Regex("/library/parts/[0-9]+/[0-9]+/file(?:\\.[A-Za-z0-9_-]+)?")
        val PLAYBACK_STATES = setOf("buffering", "playing", "paused", "stopped")

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        fun isHeaderValue(value: String): Boolean =
            value.isNotEmpty() && value.all { it.code in 33..126 }

        fun validateServerUrl(value: String): String {
            val uri = try {
                URI(value.trim())
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("Enter a valid HTTP or HTTPS Plex server address.")
            }
            require(
                (uri.scheme == "http" || uri.scheme == "https") &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535) &&
                    uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." } &&
                    !uri.rawPath.orEmpty().contains('%') &&
                    !uri.rawPath.orEmpty().contains('\\'),
            ) { "Use an HTTP or HTTPS Plex server address without credentials, query, or fragment." }
            return uri.toASCIIString().trimEnd('/')
        }
    }
}

package com.jackra1n.dooplex.plex

import com.jackra1n.dooplex.playback.PlaybackMedia
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
        require(Regex("/library/metadata/[0-9]+/children").matches(path)) {
            "A valid Plex children path is required."
        }
        require(start >= 0) { "A nonnegative Plex page offset is required." }
        readBrowsePage(
            path,
            mapOf("X-Plex-Container-Start" to start.toString(), "X-Plex-Container-Size" to "40"),
            start,
        )
    }

    suspend fun collectionPage(
        libraryKey: String,
        libraryType: String,
        start: Int,
        count: Int,
        sort: CollectionSort,
        unwatchedOnly: Boolean,
    ): PlexBrowsePage = withContext(Dispatchers.IO) {
        require(RATING_KEY.matches(libraryKey)) { "A valid Plex collection library is required." }
        require(start >= 0 && count in 1..MAX_PAGE_SIZE) { "A valid bounded Plex page is required." }
        val parameters = linkedMapOf(
            "type" to collectionType(libraryType),
            "sort" to sort.plexValue,
            "X-Plex-Container-Start" to start.toString(),
            "X-Plex-Container-Size" to count.toString(),
        )
        if (unwatchedOnly) parameters["unwatched"] = "1"
        readBrowsePage("/library/sections/$libraryKey/all", parameters, start, count, libraryType)
    }

    /**
     * PMS FirstCharacter Directory.size is the number of titles in that bucket, not its index.
     * Preserve server collation (including punctuation/Unicode); returned keys are opaque identities,
     * never URLs to follow. See plexapi.library.FirstCharacter for the endpoint/count contract.
     */
    suspend fun collectionAlphabet(libraryKey: String, libraryType: String, unwatchedOnly: Boolean): List<CollectionLetter> =
        withContext(Dispatchers.IO) {
            require(RATING_KEY.matches(libraryKey)) { "A valid Plex collection library is required." }
            val parameters = linkedMapOf("type" to collectionType(libraryType), "sort" to CollectionSort.TITLE.plexValue)
            if (unwatchedOnly) parameters["unwatched"] = "1"
            try {
                val container = JSONObject(request(
                    "/library/sections/$libraryKey/firstCharacter",
                    parameters,
                    operation = "collection alphabet",
                )).getJSONObject("MediaContainer")
                val directories = container.optJSONArray("Directory")
                if (directories == null) {
                    if (container.has("Directory") || container.opt("size")?.toString()?.toIntOrNull() != 0) {
                        throw IOException("Plex returned invalid collection alphabet information.")
                    }
                    return@withContext emptyList()
                }
                val keys = mutableSetOf<String>()
                var start = 0L
                buildList {
                    for (index in 0 until directories.length()) {
                        currentCoroutineContext().ensureActive()
                        val directory = directories.getJSONObject(index)
                        val key = directory.opt("key") as? String
                        val title = directory.opt("title") as? String
                        val count = directory.opt("size")?.toString()?.toLongOrNull()
                        if (key.isNullOrBlank() || title.isNullOrBlank() || !keys.add(key) ||
                            count == null || count !in 0..Int.MAX_VALUE.toLong() ||
                            start + count > Int.MAX_VALUE
                        ) {
                            throw IOException("Plex returned invalid collection alphabet information.")
                        }
                        if (count > 0) add(CollectionLetter(key, title, start.toInt(), count.toInt()))
                        start += count
                    }
                }
            } catch (_: JSONException) {
                throw IOException("Plex returned invalid collection alphabet information.")
            }
        }

    private fun collectionType(libraryType: String): String = when (libraryType) {
        "movie" -> "1"
        "show" -> "2"
        else -> throw IllegalArgumentException("A movie or show library is required.")
    }

    /** Refresh one show without accepting movie/season/episode metadata as its replacement. */
    suspend fun showMetadata(ratingKey: String): PlexBrowseItem = withContext(Dispatchers.IO) {
        requireRatingKey(ratingKey)
        try {
            val metadata = JSONObject(request("/library/metadata/$ratingKey", operation = "show metadata"))
                .getJSONObject("MediaContainer").optJSONArray("Metadata")
                ?: throw IOException("Plex returned no show metadata.")
            if (metadata.length() != 1) throw IOException("Plex did not return one show.")
            val source = metadata.getJSONObject(0)
            if (source.optString("ratingKey") != ratingKey || source.optString("type") != "show") {
                throw IOException("Plex returned metadata for a different show.")
            }
            parseBrowseItem(source) ?: throw IOException("Plex returned invalid show metadata.")
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid show metadata.")
        }
    }

    suspend fun movieDetails(ratingKey: String): PlexBrowseItem = withContext(Dispatchers.IO) {
        requireRatingKey(ratingKey)
        try {
            val metadata = JSONObject(request("/library/metadata/$ratingKey", operation = "movie details"))
                .getJSONObject("MediaContainer").optJSONArray("Metadata")
                ?: throw IOException("Plex returned no movie details.")
            if (metadata.length() != 1) throw IOException("Plex did not return one movie.")
            val source = metadata.getJSONObject(0)
            if (source.optString("ratingKey") != ratingKey || source.optString("type") != "movie") {
                throw IOException("Plex returned details for a different title.")
            }
            parseBrowseItem(source) ?: throw IOException("Plex returned invalid movie details.")
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid movie details.")
        }
    }

    /** Posters retain cover sizing; clear logos use transparent PNG with aspect-fit bounds. */
    fun artworkUrl(artworkPath: String, width: Int, height: Int): String {
        val standardArtwork = THUMB_PATH.matches(artworkPath) || ART_PATH.matches(artworkPath)
        val logo = !standardArtwork && PlexSourceMetadata.isLogoPath(artworkPath)
        require(standardArtwork || logo) {
            "A safe Plex movie artwork path is required."
        }
        require(width in 1..MAX_ARTWORK_DIMENSION && height in 1..MAX_ARTWORK_DIMENSION) {
            "Plex artwork dimensions must be between 1 and 2048 pixels."
        }
        val parameters = linkedMapOf(
            "url" to artworkPath,
            "width" to width.toString(),
            "height" to height.toString(),
            "minSize" to if (logo) "0" else "1",
            "upscale" to "0",
        )
        if (logo) parameters["format"] = "png"
        // Even a trusted hosted logo is fetched through this server endpoint, never with a
        // credential-bearing direct request to an external URL. Redirects remain disabled.
        return url("/photo/:/transcode", parameters)
    }

    private suspend fun readBrowsePage(
        path: String,
        parameters: Map<String, String>,
        start: Int,
        collectionCount: Int? = null,
        collectionType: String? = null,
    ): PlexBrowsePage {
        try {
            val container = JSONObject(request(path, parameters, operation = "browse"))
                .getJSONObject("MediaContainer")
            val metadata = container.optJSONArray("Metadata")
            if (collectionCount != null && (metadata?.length() ?: 0) > collectionCount) {
                throw IOException("Plex did not return a bounded collection page.")
            }
            val items = buildList {
                if (metadata != null) for (index in 0 until metadata.length()) {
                    currentCoroutineContext().ensureActive()
                    val source = metadata.getJSONObject(index)
                    val item = parseBrowseItem(source)
                    if (collectionCount != null && (item == null || source.optString("type") != collectionType)) {
                        throw IOException("Plex returned an invalid title in the collection page.")
                    }
                    if (item != null) add(item)
                }
            }
            val offset = container.optInt("offset", start)
            val total = container.optInt("totalSize", container.optInt("size", metadata?.length() ?: 0))
            if (offset < 0 || total < 0) throw IOException("Plex returned invalid page information.")
            return PlexBrowsePage(items, offset, total)
        } catch (_: JSONException) {
            throw IOException("Plex returned invalid title information.")
        }
    }

    private suspend fun parseBrowseItem(item: JSONObject): PlexBrowseItem? {
        val key = item.optString("ratingKey")
        val type = item.optString("type")
        if (!RATING_KEY.matches(key) || type !in BROWSE_TYPES) return null
        val subtitle = buildList {
            if (item.optInt("year") > 0) add(item.optInt("year").toString())
            if (type == "episode") {
                add("S${item.optInt("parentIndex")} · E${item.optInt("index")}")
                item.optString("grandparentTitle").takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (type == "season") add("${item.optInt("leafCount")} episodes")
        }.joinToString(" · ")
        val original = selectOriginal(item, required = false)
        val media = original?.media
        val duration = item.optLong("duration", media?.optLong("duration", 0) ?: 0).coerceAtLeast(0)
        val offset = item.optLong("viewOffset", 0).coerceAtLeast(0)
        return PlexBrowseItem(
            ratingKey = key,
            title = item.optString("title", "Untitled"),
            subtitle = subtitle,
            childrenPath = if (type == "show" || type == "season") "/library/metadata/$key/children" else null,
            thumbPath = item.optString("thumb").takeIf { THUMB_PATH.matches(it) },
            summary = item.optString("summary"),
            durationMs = duration,
            viewOffsetMs = if (duration > 0) offset.coerceAtMost(duration) else offset,
            watched = if (type == "show" || type == "season") {
                val episodes = item.optInt("leafCount", 0)
                episodes > 0 && item.optInt("viewedLeafCount", 0) >= episodes
            } else item.optInt("viewCount", 0) > 0,
            rating = item.optDouble("rating").takeIf { it.isFinite() },
            contentRating = item.optString("contentRating"),
            videoResolution = media?.optString("videoResolution").orEmpty(),
            artPath = (item.opt("art") as? String)?.takeIf { ART_PATH.matches(it) },
            tagline = (item.opt("tagline") as? String).orEmpty(),
            genres = item.tagNames("Genre"),
            directors = item.tagNames("Director"),
            cast = item.tagNames("Role"),
            logoPath = PlexSourceMetadata.logo(item),
            ratings = PlexSourceMetadata.ratings(item),
            videoFormat = original?.let { PlexSourceMetadata.videoFormat(it.media, it.part) }.orEmpty(),
            audioFormat = original?.let { PlexSourceMetadata.audioFormat(it.media, it.part) }.orEmpty(),
            type = type,
            libraryKey = item.optString("librarySectionID").takeIf { RATING_KEY.matches(it) },
        )
    }

    /**
     * The dedicated Continue Watching hub, then the server's other Home hubs. The dedicated hub is the
     * one that honours "remove from Continue Watching"; `/hubs` drops its own copy with
     * `excludeContinueWatching` (plx-native's measured behaviour). Hub items arrive embedded.
     */
    suspend fun homeRows(): List<PlexHomeRow> = withContext(Dispatchers.IO) {
        hubRows("/hubs/continueWatching", mapOf("count" to HOME_ROW_ITEMS), "continue watching") +
            hubRows("/hubs", mapOf("count" to HOME_ROW_ITEMS, "excludeContinueWatching" to "1"), "home")
    }

    private suspend fun hubRows(path: String, parameters: Map<String, String>, operation: String): List<PlexHomeRow> = try {
        val hubs = JSONObject(request(path, parameters, operation = operation))
            .getJSONObject("MediaContainer").optJSONArray("Hub")
        buildList {
            if (hubs != null) for (index in 0 until hubs.length()) {
                val hub = hubs.optJSONObject(index) ?: continue
                val metadata = hub.optJSONArray("Metadata") ?: continue
                val items = (0 until metadata.length()).mapNotNull { metadata.optJSONObject(it)?.let { item -> homeItem(item) } }
                if (items.isNotEmpty()) add(PlexHomeRow(
                    key = hub.optString("hubIdentifier").ifBlank { "$path#$index" },
                    title = hub.optString("title").ifBlank { "Recommended" },
                    items = items.distinctBy { it.ratingKey },
                ))
            }
        }
    } catch (_: JSONException) {
        throw IOException("Plex returned invalid Home information.")
    }

    /** Shelves show an episode or season as its show's poster, with the episode as the second line. */
    private suspend fun homeItem(source: JSONObject): PlexBrowseItem? {
        val item = parseBrowseItem(source) ?: return null
        val show = source.optString("grandparentTitle").ifBlank { source.optString("parentTitle") }
        val showPoster = sequenceOf("grandparentThumb", "parentThumb").map { source.optString(it) }
            .firstOrNull { THUMB_PATH.matches(it) }
        return when (item.type) {
            "episode" -> item.copy(
                title = show.ifBlank { item.title },
                subtitle = listOf("S${source.optInt("parentIndex")} · E${source.optInt("index")}", item.title)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                thumbPath = showPoster ?: item.thumbPath,
                artPath = (source.opt("grandparentArt") as? String)?.takeIf { ART_PATH.matches(it) } ?: item.artPath,
            )
            "season" -> item.copy(
                title = show.ifBlank { item.title },
                subtitle = item.title,
                thumbPath = item.thumbPath ?: showPoster,
            )
            else -> item
        }
    }

    private fun JSONObject.tagNames(name: String): List<String> {
        val tags = optJSONArray(name) ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (index in 0 until tags.length()) {
            val tag = (tags.optJSONObject(index)?.opt("tag") as? String)?.trim()
            if (!tag.isNullOrEmpty()) names.add(tag)
        }
        return names.toList()
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
        val original = selectOriginal(metadata, required = true)
            ?: throw IOException("This Plex item has no accessible single-file original media.")
        val version = original.media
        val part = original.part
        val partKey = part.optString("key")
        var duration = version.optLong("duration", 0)
        if (duration <= 0) duration = metadata.optLong("duration", 0)
        if (duration <= 0) duration = part.optLong("duration", 0)
        if (duration <= 0) throw IOException("Plex did not provide a usable media duration.")
        val streamUrl = url(partKey, identity + ("X-Plex-Token" to connection.token))
        currentCoroutineContext().ensureActive()
        PlaybackMedia(
            ratingKey = ratingKey,
            title = metadata.optString("title").ifBlank { "Plex $type" },
            durationMs = duration,
            resumePositionMs = metadata.optLong("viewOffset", 0).coerceIn(0, duration),
            streamUrl = streamUrl,
            partKey = partKey,
            mediaIndex = original.mediaIndex,
            partIndex = 0,
        )
    }

    private data class OriginalMedia(val media: JSONObject, val part: JSONObject, val mediaIndex: Int)

    /**
     * One original-file convention for details and playback: first non-proxy, direct, accessible
     * single-file version in server order. Missing existence/access flags mean unknown, not false.
     * Optional summaries omit unusable metadata; playback retains its actionable file errors.
     */
    private suspend fun selectOriginal(metadata: JSONObject, required: Boolean): OriginalMedia? {
        val versions = metadata.optJSONArray("Media")
        if (versions == null) {
            if (required) throw IOException("This Plex item has no original media file.")
            return null
        }
        var hasMultipart = false
        for (mediaIndex in 0 until versions.length()) {
            currentCoroutineContext().ensureActive()
            val version = versions.optJSONObject(mediaIndex) ?: continue
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
            if (!PART_KEY.matches(partKey)) {
                if (required) requirePartKey(partKey)
                return null
            }
            return OriginalMedia(version, part, mediaIndex)
        }
        if (required) {
            if (hasMultipart) {
                throw IOException("Multipart original media is unsupported; choose a single-file version.")
            }
            throw IOException("This Plex item has no accessible single-file original media.")
        }
        return null
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
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw PlexRequestException("Plex server access was denied (HTTP $status); reconnect to the server.")
            }
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
        const val MAX_PAGE_SIZE = 200
        const val MAX_ARTWORK_DIMENSION = 2048
        val RATING_KEY = Regex("[0-9]+")
        val PART_KEY = Regex("/library/parts/[0-9]+/[0-9]+/file(?:\\.[A-Za-z0-9_-]+)?")
        val THUMB_PATH = Regex("/library/metadata/[0-9]+/thumb(?:/[0-9]+)?")
        val ART_PATH = Regex("/library/metadata/[0-9]+/art(?:/[0-9]+)?")
        val BROWSE_TYPES = setOf("movie", "show", "season", "episode")
        const val HOME_ROW_ITEMS = "24"
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

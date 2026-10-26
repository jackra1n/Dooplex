package com.jackra1n.dooplex

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import org.json.JSONObject

/**
 * Optional details from Plex's Image/Rating arrays and original-file streams. Unknown badges,
 * unsafe artwork and absent stream evidence stay absent rather than borrowing another source.
 * Contracts: https://github.com/plexinc/tmdb-example-provider/blob/main/docs/Metadata.md and
 * https://python-plexapi.readthedocs.io/en/latest/modules/media.html (selected/default streams).
 */
internal object PlexSourceMetadata {
    private val localLogo = Regex("/library/metadata/[0-9]+/clearLogo(?:/[0-9]+)?")
    private val hostedImage = Regex("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:png|webp|jpg|jpeg)")
    private val numericResolution = Regex("[0-9]{3,4}[pi]?")
    private val channelLayout = Regex("[1-9][0-9]?\\.[0-9](?:\\.[0-9])?")
    private val atmos = Regex("\\batmos\\b", RegexOption.IGNORE_CASE)
    private val dtsX = Regex("\\bdts[ :_-]*x\\b", RegexOption.IGNORE_CASE)
    private val dolbyVision = Regex("\\bdolby[ ._-]*vision\\b|\\bdovi\\b", RegexOption.IGNORE_CASE)
    private val hdr10Plus = Regex("\\bhdr10\\+|\\bhdr10[ _-]*plus\\b", RegexOption.IGNORE_CASE)
    private val hdr10 = Regex("\\bhdr10\\b", RegexOption.IGNORE_CASE)
    private val hdr = Regex("\\bhdr\\b", RegexOption.IGNORE_CASE)
    private val hlg = Regex("\\bhlg\\b", RegexOption.IGNORE_CASE)

    /** No direct external fetch: accepted hosted assets are only input to PMS's image proxy. */
    fun isLogoPath(path: String): Boolean {
        if (localLogo.matches(path)) return true
        val uri = try { URI(path) } catch (_: URISyntaxException) { return false }
        return uri.scheme == "https" && uri.host == "metadata-static.plex.tv" &&
            uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawFragment == null && hostedImage.matches(uri.rawPath.orEmpty())
    }

    fun logo(item: JSONObject): String? {
        val images = item.optJSONArray("Image")
        if (images != null) for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            if (image.optString("type") != "clearLogo") continue
            val path = image.opt("url") as? String ?: continue
            if (isLogoPath(path)) return path
        }
        return (item.opt("clearLogo") as? String)?.takeIf(::isLogoPath)
    }

    /** Array entries win over legacy fields; deduplicate by genuine source and rating role. */
    fun ratings(item: JSONObject): List<PlexMediaRating> {
        val result = LinkedHashMap<Pair<String, String>, PlexMediaRating>()
        fun add(image: String, type: String, value: Double) {
            if ((type != "critic" && type != "audience") || !value.isFinite() || value !in 0.0..10.0) return
            val source = when (image) {
                "imdb://image.rating" -> "IMDb"
                "rottentomatoes://image.rating.ripe", "rottentomatoes://image.rating.rotten" ->
                    if (type == "critic") "Rotten Tomatoes" else return
                "rottentomatoes://image.rating.upright", "rottentomatoes://image.rating.spilled" ->
                    if (type == "audience") "Rotten Tomatoes" else return
                else -> return
            }
            val key = source to type
            if (!result.containsKey(key)) result[key] = PlexMediaRating(source, type, value)
        }
        val ratings = item.optJSONArray("Rating")
        if (ratings != null) for (index in 0 until ratings.length()) {
            val rating = ratings.optJSONObject(index) ?: continue
            add(rating.optString("image"), rating.optString("type"), rating.optDouble("value"))
        }
        add(item.optString("ratingImage"), "critic", item.optDouble("rating"))
        add(item.optString("audienceRatingImage"), "audience", item.optDouble("audienceRating"))
        return result.values.toList()
    }

    private fun preferredStream(part: JSONObject, type: Int): JSONObject? {
        val streams = part.optJSONArray("Stream") ?: return null
        var first: JSONObject? = null
        var default: JSONObject? = null
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            if (stream.optInt("streamType") != type) continue
            if (stream.flag("selected")) return stream
            if (first == null) first = stream
            if (default == null && stream.flag("default")) default = stream
        }
        return default ?: first
    }

    fun videoFormat(media: JSONObject, part: JSONObject): String {
        val stream = preferredStream(part, 1)
        val sourceResolution = media.text("videoResolution").ifEmpty {
            val height = stream?.optInt("height", 0)?.takeIf { it > 0 } ?: media.optInt("height", 0)
            height.takeIf { it > 0 }?.toString().orEmpty()
        }
        val resolution = when (val value = sourceResolution.lowercase(Locale.ROOT)) {
            "4k", "2160", "2160p" -> "4K"
            "8k", "4320", "4320p" -> "8K"
            "1080", "1080p" -> "1080p"
            "1080i" -> "1080i"
            "720", "720p" -> "720p"
            "576", "576p" -> "576p"
            "480", "480p" -> "480p"
            "sd" -> "SD"
            else -> value.takeIf { numericResolution.matches(it) }.orEmpty()
        }
        if (stream == null) return resolution
        val markers = stream.markers()
        val formats = ArrayList<String>(4)
        if (resolution.isNotEmpty()) formats.add(resolution)
        val vision = stream.flag("DOVIPresent") || stream.flag("DOVIRPUPresent") ||
            stream.optInt("DOVIProfile", 0) > 0 || dolbyVision.containsMatchIn(markers)
        if (vision) formats.add("Dolby Vision")
        val transfer = stream.text("colorTrc").lowercase(Locale.ROOT)
        val pq = transfer == "smpte2084" || transfer == "smpte-st-2084" || transfer == "pq"
        val hdrCompatibleBase = !vision || stream.optInt("DOVIBLCompatID", 0) == 1
        when {
            hdr10Plus.containsMatchIn(markers) -> formats.add("HDR10+")
            hdr10.containsMatchIn(markers) || (pq && hdrCompatibleBase) -> formats.add("HDR10")
            hlg.containsMatchIn(markers) || transfer == "arib-std-b67" || transfer == "hlg" -> formats.add("HLG")
            !vision && hdr.containsMatchIn(markers) -> formats.add("HDR")
        }
        return formats.joinToString(" · ")
    }

    /** Selected, then default, then first audio stream; Media/Part fields only fill absent data. */
    fun audioFormat(media: JSONObject, part: JSONObject): String {
        val stream = preferredStream(part, 2)
        val codec = stream?.text("codec").orEmpty().ifEmpty { media.text("audioCodec") }.lowercase(Locale.ROOT)
        val profile = stream?.text("profile").orEmpty().ifEmpty {
            part.text("audioProfile").ifEmpty { media.text("audioProfile") }
        }.lowercase(Locale.ROOT)
        val markers = stream?.markers().orEmpty() + " " + profile
        val codecName = when (codec) {
            "truehd" -> "Dolby TrueHD"
            "eac3", "e-ac-3" -> "Dolby Digital Plus"
            "ac3", "ac-3" -> "Dolby Digital"
            "dca", "dts" -> when (profile) {
                "ma", "dts-hd ma", "dts_hd_ma" -> "DTS-HD MA"
                "hra", "dts-hd hra", "dts_hd_hra" -> "DTS-HD HRA"
                else -> "DTS"
            }
            "aac" -> "AAC"
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "mp3" -> "MP3"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            else -> if (codec.startsWith("pcm")) "PCM" else codec.uppercase(Locale.ROOT)
        }
        val formats = ArrayList<String>(3)
        if (codecName.isNotEmpty()) formats.add(codecName)
        when {
            atmos.containsMatchIn(markers) -> formats.add("Atmos")
            dtsX.containsMatchIn(markers) -> formats.add("DTS:X")
        }
        val channels = stream?.optInt("channels", 0)?.takeIf { it > 0 } ?: media.optInt("audioChannels", 0)
        val layout = stream?.text("audioChannelLayout").orEmpty().substringBefore('(')
        val channelLabel = when {
            channelLayout.matches(layout) -> layout
            channels == 1 -> "1.0"
            channels == 2 -> "2.0"
            channels == 6 -> "5.1"
            channels == 8 -> "7.1"
            channels in 1..32 -> "$channels ch"
            else -> ""
        }
        if (channelLabel.isNotEmpty()) formats.add(channelLabel)
        return formats.joinToString(" · ")
    }

    private fun JSONObject.markers(): String =
        listOf(text("displayTitle"), text("extendedDisplayTitle"), text("title"), text("profile")).joinToString(" ")

    private fun JSONObject.text(name: String): String =
        (opt(name) as? String)?.trim()?.take(120)?.filterNot { it.isISOControl() }.orEmpty()

    private fun JSONObject.flag(name: String): Boolean =
        opt(name)?.toString().let { it == "1" || it.equals("true", ignoreCase = true) }
}

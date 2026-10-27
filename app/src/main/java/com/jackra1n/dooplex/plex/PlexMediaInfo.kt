package com.jackra1n.dooplex.plex

import java.util.Locale
import org.json.JSONObject

data class MediaInfoSection(val title: String, val rows: List<Pair<String, String>>)

/** Every version, file and stream Plex reports for a movie or episode, as label/value sections. */
internal object PlexMediaInfo {
    fun sections(item: JSONObject): List<MediaInfoSection> = buildList {
        val versions = item.optJSONArray("Media") ?: return@buildList
        for (v in 0 until versions.length()) {
            val media = versions.optJSONObject(v) ?: continue
            val parts = media.optJSONArray("Part") ?: continue
            for (p in 0 until parts.length()) {
                val part = parts.optJSONObject(p) ?: continue
                val prefix = listOfNotNull(
                    "Version ${v + 1}".takeIf { versions.length() > 1 },
                    "Part ${p + 1}".takeIf { parts.length() > 1 },
                ).joinToString("") { "$it · " }
                add(file(prefix, media, part))
                addAll(streams(prefix, part))
            }
        }
    }

    private fun file(prefix: String, media: JSONObject, part: JSONObject) = MediaInfoSection("${prefix}File", rows(
        "Path" to part.text("file"),
        "Container" to part.text("container").ifEmpty { media.text("container") }.uppercase(Locale.ROOT),
        "Size" to size(part.optLong("size")),
        "Duration" to duration(part.optLong("duration").takeIf { it > 0 } ?: media.optLong("duration")),
        "Bitrate" to bitrate(media.optInt("bitrate")),
    ))

    private fun streams(prefix: String, part: JSONObject): List<MediaInfoSection> {
        val streams = part.optJSONArray("Stream") ?: return emptyList()
        val all = (0 until streams.length()).mapNotNull(streams::optJSONObject)
        val video = all.filter { it.optInt("streamType") == 1 }
        val audio = all.filter { it.optInt("streamType") == 2 }
        val subtitles = all.filter { it.optInt("streamType") == 3 }
        return buildList {
            video.forEachIndexed { index, stream ->
                add(MediaInfoSection(prefix + numbered("Video", index, video.size), video(stream)))
            }
            audio.forEachIndexed { index, stream ->
                val title = listOf(numbered("Audio", index, audio.size), stream.text("language")).filter { it.isNotEmpty() }
                add(MediaInfoSection(prefix + title.joinToString(" · "), audio(stream)))
            }
            if (subtitles.isNotEmpty()) add(MediaInfoSection("${prefix}Subtitles", subtitles.map { stream ->
                stream.text("language").ifEmpty { "Unknown" } to listOf(
                    stream.text("codec").uppercase(Locale.ROOT),
                    stream.text("title"),
                    "Forced".takeIf { stream.flag("forced") }.orEmpty(),
                    "External".takeIf { stream.has("key") }.orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
            }))
        }
    }

    private fun video(stream: JSONObject): List<Pair<String, String>> {
        val width = stream.optInt("width")
        val height = stream.optInt("height")
        val vision = stream.flag("DOVIPresent")
        return rows(
            "Name" to stream.text("extendedDisplayTitle").ifEmpty { stream.text("displayTitle") },
            "Codec" to codec(stream),
            "Resolution" to if (width > 0 && height > 0) "$width × $height" else "",
            "Frame rate" to stream.optDouble("frameRate").takeIf { it.isFinite() && it > 0 }?.let { "%.3f fps".format(Locale.ROOT, it) }.orEmpty(),
            "Bit depth" to stream.optInt("bitDepth").takeIf { it > 0 }?.let { "$it-bit" }.orEmpty(),
            "Bitrate" to bitrate(stream.optInt("bitrate")),
            "Dolby Vision" to if (vision) listOf(
                stream.optInt("DOVIProfile").takeIf { it > 0 }?.let { "Profile $it" },
                stream.optInt("DOVILevel").takeIf { it > 0 }?.let { "level $it" },
                stream.optInt("DOVIBLCompatID").takeIf { it > 0 }?.let { "compatibility $it" },
            ).filterNotNull().joinToString(", ").ifEmpty { "Yes" } else "",
            "Color" to listOf("colorPrimaries", "colorTrc", "colorSpace", "colorRange")
                .map { stream.text(it) }.filter { it.isNotEmpty() }.joinToString(" · "),
            "Chroma" to stream.text("chromaSubsampling"),
            "Scan" to stream.text("scanType"),
        )
    }

    private fun audio(stream: JSONObject): List<Pair<String, String>> = rows(
        "Name" to stream.text("extendedDisplayTitle").ifEmpty { stream.text("displayTitle") },
        "Codec" to codec(stream),
        "Channels" to stream.text("audioChannelLayout").ifEmpty { stream.optInt("channels").takeIf { it > 0 }?.toString().orEmpty() },
        "Bitrate" to bitrate(stream.optInt("bitrate")),
        "Sample rate" to stream.optInt("samplingRate").takeIf { it > 0 }?.let { "%.1f kHz".format(Locale.ROOT, it / 1000.0) }.orEmpty(),
        "Bit depth" to stream.optInt("bitDepth").takeIf { it > 0 }?.let { "$it-bit" }.orEmpty(),
        "Title" to stream.text("title"),
        "Default" to if (stream.flag("default")) "Yes" else "",
    )

    private fun codec(stream: JSONObject) =
        listOf(stream.text("codec").uppercase(Locale.ROOT), stream.text("profile")).filter { it.isNotEmpty() }.joinToString(" · ")

    private fun numbered(name: String, index: Int, count: Int) = if (count > 1) "$name ${index + 1}" else name

    private fun rows(vararg pairs: Pair<String, String>) = pairs.filter { it.second.isNotBlank() }

    private fun bitrate(kbps: Int) = when {
        kbps >= 1000 -> "%.1f Mbps".format(Locale.ROOT, kbps / 1000.0)
        kbps > 0 -> "$kbps kbps"
        else -> ""
    }

    private fun size(bytes: Long) = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes > 0 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> ""
    }

    private fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val seconds = ms / 1000
        return "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun JSONObject.text(name: String): String = opt(name)?.toString()?.trim().orEmpty()

    private fun JSONObject.flag(name: String): Boolean =
        opt(name)?.toString().let { it == "1" || it.equals("true", ignoreCase = true) }
}

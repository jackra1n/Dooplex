package com.jackra1n.dooplex.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

/**
 * Hands playback to a player app: [ZidooPlayer] when the box has it, otherwise whichever video player
 * Android opens. Zidoo's player, VLC, MX Player and Just Player all read these extras.
 */
internal object PlayerHandoff {
    class Launch(val intent: Intent, val liveStatus: Boolean)

    fun prepare(context: Context, media: PlaybackMedia): Launch {
        originalMediaUri(media.streamUrl)
        require(media.resumePositionMs in 0..Int.MAX_VALUE.toLong()) {
            "The video player requires a resume position between zero and 2,147,483,647 milliseconds."
        }
        val zidoo = ZidooPlayer(context).component()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(media.streamUrl), "video/*")
            if (zidoo != null) {
                component = zidoo
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            putExtra("title", media.title)
            putExtra("from_start", media.resumePositionMs == 0L)
            if (media.resumePositionMs > 0) {
                putExtra("position", media.resumePositionMs.toInt())
            }
            putExtra("return_result", true)
        }
        return Launch(intent, liveStatus = zidoo != null)
    }

    /**
     * Where the player stopped. Zidoo's, MX Player and Just Player return `position`, VLC
     * `extra_position`; MX Player and Just Player send no position after playing to the end.
     */
    @Suppress("DEPRECATION")
    fun readResult(data: Intent?, durationMs: Long): Long? {
        val extras = data?.extras ?: return null
        val (finished, value) = try {
            (extras.getString("end_by") == "playback_completion") to (extras.get("position") ?: extras.get("extra_position"))
        } catch (_: android.os.BadParcelableException) {
            throw IllegalStateException("The video player returned unreadable playback-result data.")
        }
        if (finished) return durationMs.takeIf { it > 0 }
        val position = when (value) {
            null -> return null
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalStateException("The video player returned an unsupported position type.")
        }
        if (position < 0) {
            throw IllegalStateException("The video player returned a negative position.")
        }
        return position // Zero is a real position, not a missing result.
    }
}

internal fun originalMediaUri(url: String): URI {
    val uri = try {
        URI(url).normalize()
    } catch (_: Exception) {
        throw IllegalArgumentException("Plex did not provide a valid original-media HTTP URL.")
    }
    require((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank() && !uri.rawPath.isNullOrEmpty() && uri.rawFragment == null
    ) { "Plex must provide an absolute HTTP(S) original-media URL without a fragment." }
    return uri
}

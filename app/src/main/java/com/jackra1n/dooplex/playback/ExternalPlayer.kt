package com.jackra1n.dooplex.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The player app playback is handed to: Zidoo's own when the box has it, otherwise whichever video
 * player Android opens. Zidoo's, VLC, MX Player and Just Player all read these extras; only Zidoo's
 * reports live progress (see [readStatus]).
 */
class ExternalPlayer(private val context: Context) {
    class Launch(val intent: Intent, val liveStatus: Boolean)

    fun createLaunch(media: PlaybackMedia): Launch {
        mediaUri(media.streamUrl)
        require(media.resumePositionMs in 0..Int.MAX_VALUE.toLong()) {
            "The video player requires a resume position between zero and 2,147,483,647 milliseconds."
        }
        val zidoo = ZIDOO_COMPONENTS.firstOrNull { isAvailable(it) }
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
     * Reads matching native playback proof, trying the firmware alias when the primary is idle
     * or incomplete. Missing identity returns null and must be sampled again until player return;
     * it never proves that this item is playing. Malformed or mismatched telemetry remains an error.
     */
    suspend fun readStatus(media: PlaybackMedia): NativePlaybackStatus? = withContext(Dispatchers.IO) {
        val expected = mediaUri(media.streamUrl)
        var sawUnavailable = false
        for (endpoint in STATUS_ENDPOINTS) {
            val response = requestStatus(endpoint) ?: continue // HTTP 404: try the firmware alias.
            val status = NativeTelemetryDecoder.decode(response, expected)
            if (status != null) return@withContext status
            sawUnavailable = true
        }
        if (sawUnavailable) return@withContext null
        throw IllegalStateException(
            "Neither native getPlayStatus endpoint exists on localhost:9529. " +
                "Enable Zidoo's network control service or use firmware with native video status support."
        )
    }

    @Suppress("DEPRECATION")
    private fun isAvailable(component: ComponentName): Boolean = try {
        val activity = context.packageManager.getActivityInfo(component, 0)
        activity.enabled && activity.exported && activity.applicationInfo.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun requestStatus(endpoint: String): JSONObject? {
        val connection = (URL("http://127.0.0.1:9529/$endpoint/getPlayStatus")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "Zidoo's native status API returned HTTP $code. " +
                        "Check the local network control service; live Plex reporting is unavailable."
                )
            }
            val body = connection.inputStream.use { input ->
                // Use only Android 11 APIs; cap both size and total body-read time.
                val bytes = ByteArray(MAX_STATUS_BYTES + 1)
                var size = 0
                val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
                while (size < bytes.size) {
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw java.net.SocketTimeoutException()
                    connection.readTimeout = remaining.toInt()
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                if (size > MAX_STATUS_BYTES) NativeTelemetryDecoder.unsupported("oversized response")
                String(bytes, 0, size, Charsets.UTF_8)
            }
            return try {
                JSONObject(body)
            } catch (_: org.json.JSONException) {
                NativeTelemetryDecoder.unsupported("invalid JSON response")
            }
        } catch (_: IOException) {
            throw IllegalStateException(
                "Cannot read Zidoo's native status API on localhost:9529. " +
                    "Check that the firmware's network control service is enabled and " +
                    "Dooplex permits cleartext loopback HTTP."
            )
        } catch (_: SecurityException) {
            throw IllegalStateException(
                "Android denied access to Zidoo's native status API. Check Dooplex's INTERNET permission."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun mediaUri(url: String): URI {
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

    companion object {
        private const val MAX_STATUS_BYTES = 64 * 1024
        private val ZIDOO_COMPONENTS = listOf(
            ComponentName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity"),
            ComponentName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity"),
        )
        private val STATUS_ENDPOINTS = listOf("ZidooVideoPlay", "VideoPlay")

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
}

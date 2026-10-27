package com.jackra1n.dooplex.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Zidoo's own player, the one Dooplex is built for: it plays every format the box supports, and it is
 * the only player whose progress can be read live (from the box's status service on port 9529).
 */
class ZidooPlayer(private val context: Context) {
    /** The firmware's player activity, or null on a device without one. */
    fun component(): ComponentName? = COMPONENTS.firstOrNull { isAvailable(it) }

    /**
     * Reads matching native playback proof, trying the firmware alias when the primary is idle
     * or incomplete. Missing identity returns null and must be sampled again until player return;
     * it never proves that this item is playing. Malformed or mismatched telemetry remains an error.
     */
    suspend fun readStatus(media: PlaybackMedia): NativePlaybackStatus? = withContext(Dispatchers.IO) {
        val expected = originalMediaUri(media.streamUrl)
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

    companion object {
        private const val MAX_STATUS_BYTES = 64 * 1024
        private val COMPONENTS = listOf(
            ComponentName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity"),
            ComponentName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity"),
        )
        private val STATUS_ENDPOINTS = listOf("ZidooVideoPlay", "VideoPlay")
    }
}

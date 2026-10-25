package com.jackra1n.dooplex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ZidooPlayer(private val context: Context) {
    fun createIntent(media: PlaybackMedia): Intent {
        mediaUri(media.streamUrl)
        require(media.resumePositionMs in 0..Int.MAX_VALUE.toLong()) {
            "The native Zidoo player requires a resume position between zero and 2,147,483,647 milliseconds."
        }
        val component = PLAYER_COMPONENTS.firstOrNull { isAvailable(it) }
            ?: throw IllegalStateException(
                "Zidoo's native player is unavailable. Enable or install the firmware's com.zidoo.player " +
                    "or com.android.gallery3d player, and allow Dooplex to discover these packages. " +
                    "No generic video player will be used."
            )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(media.streamUrl), "video/*")
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            putExtra("title", media.title)
            putExtra("from_start", media.resumePositionMs == 0L)
            if (media.resumePositionMs > 0) {
                putExtra("position", media.resumePositionMs.toInt())
            }
            putExtra("return_result", true)
        }
    }

    suspend fun readStatus(media: PlaybackMedia): NativePlaybackStatus? = withContext(Dispatchers.IO) {
        val expected = mediaUri(media.streamUrl)
        var sawIdle = false
        for (endpoint in STATUS_ENDPOINTS) {
            val response = requestStatus(endpoint) ?: continue // HTTP 404: try the firmware alias.
            when (requiredLong(response, "status")) {
                806L -> {
                    sawIdle = true
                    continue
                }
                200L -> Unit
                else -> throw IllegalStateException(
                    "Zidoo's native status API returned an unsupported error code. " +
                        "Check the firmware's getPlayStatus support; live Plex reporting is unavailable."
                )
            }
            val video = response.optJSONObject("video") ?: unsupported("missing video information")
            val path = video.opt("path") as? String ?: unsupported("missing media path")
            if (path.isBlank()) unsupported("empty media path")
            if (!NativeMediaIdentity.matches(path, expected)) {
                throw IllegalStateException(
                    "Zidoo is reporting a different media path, or a path that cannot be matched " +
                        "to this Plex original. No playback state will be attributed to this item."
                )
            }
            // Vendor changeStatus documentation: 0 = pause, 1 = play. The established
            // zidoo-player client also decodes video.status == 1 as playing.
            val state = when (requiredLong(video, "status")) {
                0L -> "paused"
                1L -> "playing"
                else -> unsupported("unknown numeric playback state")
            }
            val position = requiredLong(video, "currentPosition")
            val duration = requiredLong(video, "duration")
            if (position < 0 || duration <= 0) unsupported("invalid playback timing")
            return@withContext NativePlaybackStatus(state, position, duration)
        }
        if (sawIdle) return@withContext null
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
                if (size > MAX_STATUS_BYTES) unsupported("oversized response")
                String(bytes, 0, size, Charsets.UTF_8)
            }
            return try {
                JSONObject(body)
            } catch (_: org.json.JSONException) {
                unsupported("invalid JSON response")
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

    private fun requiredLong(json: JSONObject, key: String): Long = when (val value = json.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> unsupported("missing or non-integer $key")
    }

    private fun unsupported(detail: String): Nothing = throw IllegalStateException(
        "Unsupported Zidoo native telemetry ($detail). " +
            "This firmware must expose a matching video.path, status 0/1, currentPosition and duration " +
            "in milliseconds before live Plex reporting can be used."
    )

    companion object {
        private const val MAX_STATUS_BYTES = 64 * 1024
        private val PLAYER_COMPONENTS = listOf(
            ComponentName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity"),
            ComponentName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity"),
        )
        private val STATUS_ENDPOINTS = listOf("ZidooVideoPlay", "VideoPlay")

        @Suppress("DEPRECATION")
        fun readResult(data: Intent?): Long? {
            if (data == null || !data.hasExtra("position")) return null
            val position = try {
                when (val value = data.extras?.get("position")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw IllegalStateException("Zidoo returned an unsupported result position type.")
                }
            } catch (_: android.os.BadParcelableException) {
                throw IllegalStateException("Zidoo returned unreadable playback-result data.")
            }
            if (position < 0) {
                throw IllegalStateException("Zidoo returned a negative playback-result position.")
            }
            return position // Zero is a real position, not a missing result.
        }
    }
}

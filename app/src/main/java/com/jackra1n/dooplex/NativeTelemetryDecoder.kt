package com.jackra1n.dooplex

import org.json.JSONObject
import java.net.URI

/** Decodes verified native telemetry; idle or missing media identity is unavailable, not playback proof. */
internal object NativeTelemetryDecoder {
    fun decode(response: JSONObject, expected: URI): NativePlaybackStatus? {
        when (requiredLong(response, "status")) {
            806L -> return null
            200L -> Unit
            else -> throw IllegalStateException(
                "Zidoo's native status API returned an unsupported error code. " +
                    "Check the firmware's getPlayStatus support; live Plex reporting is unavailable."
            )
        }
        val videoValue = response.opt("video")
        if (videoValue == null || videoValue == JSONObject.NULL) return null
        val video = videoValue as? JSONObject ?: unsupported("invalid video information")
        val pathValue = video.opt("path")
        if (pathValue == null || pathValue == JSONObject.NULL) return null
        val path = pathValue as? String ?: unsupported("non-string media path")
        if (path.isBlank()) return null
        if (!NativeMediaIdentity.matches(path, expected)) {
            throw IllegalStateException(
                "Zidoo is reporting a different media path, or a path that cannot be matched " +
                    "to this Plex original. No playback state will be attributed to this item."
            )
        }
        // Vendor changeStatus documentation and zidoo-player: 0 = pause, 1 = play.
        val state = when (requiredLong(video, "status")) {
            0L -> "paused"
            1L -> "playing"
            else -> unsupported("unknown numeric playback state")
        }
        val position = requiredLong(video, "currentPosition")
        val duration = requiredLong(video, "duration")
        if (position < 0 || duration <= 0) unsupported("invalid playback timing")
        return NativePlaybackStatus(state, position, duration)
    }

    private fun requiredLong(json: JSONObject, key: String): Long = when (val value = json.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> unsupported("missing or non-integer $key")
    }

    fun unsupported(detail: String): Nothing = throw IllegalStateException(
        "Unsupported Zidoo native telemetry ($detail). " +
            "This firmware must expose a matching video.path, status 0/1, currentPosition and duration " +
            "in milliseconds before live Plex reporting can be used."
    )
}

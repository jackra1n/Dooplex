package com.jackra1n.dooplex

data class PlexConnection(
    val serverUrl: String,
    val token: String,
    val clientId: String,
)

data class PlaybackMedia(
    val ratingKey: String,
    val title: String,
    val durationMs: Long,
    val resumePositionMs: Long,
    val streamUrl: String,
    val partKey: String,
    val mediaIndex: Int = 0,
    val partIndex: Int = 0,
)

data class NativePlaybackStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
)

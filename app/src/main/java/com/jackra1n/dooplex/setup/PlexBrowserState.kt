package com.jackra1n.dooplex.setup

import android.graphics.Bitmap
import com.jackra1n.dooplex.plex.PlexPin
import com.jackra1n.dooplex.plex.PlexServer

enum class BrowserStage { SIGN_IN, SERVERS, TITLES }

data class PlexBrowserState(
    val stage: BrowserStage = BrowserStage.SIGN_IN,
    val accountName: String = "",
    val pin: PlexPin? = null,
    val pinQr: Bitmap? = null,
    val servers: List<PlexServer> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

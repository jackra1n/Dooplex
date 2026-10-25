package com.jackra1n.dooplex

import java.io.IOException

data class PlexPin(val id: String, val code: String, val expiresAtMs: Long)
data class PlexAccount(val token: String, val displayName: String)
data class PlexServerEndpoint(val uri: String, val local: Boolean, val relay: Boolean)
data class PlexServer(
    val id: String,
    val name: String,
    val accessToken: String,
    val connections: List<PlexServerEndpoint>,
)
data class PlexLibrary(val key: String, val title: String, val type: String)
data class PlexBrowseItem(
    val ratingKey: String,
    val title: String,
    val subtitle: String,
    val childrenPath: String? = null,
)
data class PlexBrowsePage(val items: List<PlexBrowseItem>, val start: Int, val total: Int) {
    val hasNext: Boolean get() = items.isNotEmpty() && start + items.size < total
}

class PlexAuthenticationException(message: String) : IOException(message)

enum class BrowserStage { SIGN_IN, SERVERS, LIBRARIES, TITLES }

data class PlexBrowserState(
    val stage: BrowserStage = BrowserStage.SIGN_IN,
    val heading: String = "Connect your Plex account",
    val accountName: String = "",
    val pin: PlexPin? = null,
    val servers: List<PlexServer> = emptyList(),
    val libraries: List<PlexLibrary> = emptyList(),
    val page: PlexBrowsePage? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val canGoBack: Boolean = false,
)

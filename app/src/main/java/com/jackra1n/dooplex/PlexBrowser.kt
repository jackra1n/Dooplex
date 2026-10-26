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
/**
 * Optional artwork uses validated local Plex metadata paths; missing text and credits stay empty.
 * Genre, director and cast names are deduplicated Plex tags in server order.
 */
data class PlexBrowseItem(
    val ratingKey: String,
    val title: String,
    val subtitle: String,
    val childrenPath: String? = null,
    val thumbPath: String? = null,
    val summary: String = "",
    val durationMs: Long = 0,
    val viewOffsetMs: Long = 0,
    val watched: Boolean = false,
    val rating: Double? = null,
    val contentRating: String = "",
    val videoResolution: String = "",
    val artPath: String? = null,
    val tagline: String = "",
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
)
data class PlexBrowsePage(val items: List<PlexBrowseItem>, val start: Int, val total: Int) {
    val hasNext: Boolean get() = items.isNotEmpty() && start + items.size < total
}

/** Server-ordered title-sort bucket; [start] sums preceding bucket counts, not display initials. */
data class CollectionLetter(val key: String, val title: String, val start: Int, val count: Int)

/** A new id represents every activation, including repeated jumps to the same index. */
data class CollectionJump(val id: Long, val index: Int)

enum class CollectionSort(val title: String, val plexValue: String) {
    TITLE("Title A–Z", "titleSort:asc"),
    ADDED("Recently added", "addedAt:desc"),
    RELEASE("Release date", "originallyAvailableAt:desc"),
    RATING("Rating", "rating:desc"),
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
    val busy: Boolean = false,
    val error: String? = null,
    val canGoBack: Boolean = false,
)

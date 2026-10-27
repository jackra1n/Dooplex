package com.jackra1n.dooplex.plex

import java.io.IOException

data class PlexPin(val id: String, val code: String, val expiresAtMs: Long, val qrUrl: String)
data class PlexAccount(val token: String, val displayName: String, val avatarUrl: String? = null)
data class PlexServerEndpoint(val uri: String, val local: Boolean, val relay: Boolean)
data class PlexServer(
    val id: String,
    val name: String,
    val accessToken: String,
    val connections: List<PlexServerEndpoint>,
)
data class PlexLibrary(val key: String, val title: String, val type: String)
data class WatchlistEntry(val guid: String, val type: String, val title: String)
/**
 * Optional artwork is restricted to local metadata paths or trusted Plex-hosted clear-logo assets.
 * Credits retain server order. Ratings carry explicit provenance on Plex's 0..10 scale; source
 * video/audio summaries describe the selected accessible original, never the player's output.
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
    val logoPath: String? = null,
    val ratings: List<PlexMediaRating> = emptyList(),
    val videoFormat: String = "",
    val audioFormat: String = "",
    val type: String = "",
    val libraryKey: String? = null,
)

/** One server-arranged Home shelf, Continue Watching first; empty shelves are never kept. */
data class PlexHomeRow(val key: String, val title: String, val items: List<PlexBrowseItem>)

/** Explicit Plex rating badge identity; a generic title rating does not establish its source. */
data class PlexMediaRating(val source: String, val type: String, val value: Double)
data class PlexBrowsePage(val items: List<PlexBrowseItem>, val start: Int, val total: Int) {
    val hasNext: Boolean get() = items.isNotEmpty() && start + items.size < total
}

/** Server-ordered title-sort bucket; [start] sums preceding bucket counts, not display initials. */
data class CollectionLetter(val key: String, val title: String, val start: Int, val count: Int)

/** A new id represents every activation, including repeated jumps to the same index. */
data class CollectionJump(val id: Long, val index: Int)

/**
 * Each [label] is one row of the sort menu, listed in its default direction first; choosing the
 * current row again flips to its partner. No random order: pages are loaded separately, and a
 * random sort would differ on every page.
 */
enum class CollectionSort(val label: String, val direction: String, val plexValue: String) {
    TITLE("Title", "A–Z", "titleSort:asc"),
    TITLE_DESC("Title", "Z–A", "titleSort:desc"),
    ADDED("Date added", "Newest", "addedAt:desc"),
    ADDED_ASC("Date added", "Oldest", "addedAt:asc"),
    RELEASE("Release date", "Newest", "originallyAvailableAt:desc"),
    RELEASE_ASC("Release date", "Oldest", "originallyAvailableAt:asc"),
    RATING("Rating", "Highest", "rating:desc"),
    RATING_ASC("Rating", "Lowest", "rating:asc"),
    VIEWED("Date viewed", "Latest", "lastViewedAt:desc"),
    VIEWED_ASC("Date viewed", "Earliest", "lastViewedAt:asc"),
    PLAYS("Plays", "Most", "viewCount:desc"),
    PLAYS_ASC("Plays", "Fewest", "viewCount:asc");

    val flipped: CollectionSort get() = entries.first { it.label == label && it != this }
}

class PlexAuthenticationException(message: String) : IOException(message)

data class PlexConnection(
    val serverUrl: String,
    val token: String,
    val clientId: String,
)

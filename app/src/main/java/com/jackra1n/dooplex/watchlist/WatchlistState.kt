package com.jackra1n.dooplex.watchlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jackra1n.dooplex.details.MovieDetailsLoader
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.plex.PlexAccountClient
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexSourceMetadata
import com.jackra1n.dooplex.plex.WatchlistEntry
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A watchlist title: this server's copy when it has one, otherwise what Plex knows about it. */
data class WatchlistTitle(val item: PlexBrowseItem, val onServer: Boolean)

/**
 * The account's Plex watchlist in its own order. Titles are matched to this server by their
 * `plex://` id; the rest still show, but can only be read about or removed.
 */
class WatchlistState(
    private val accountClient: PlexAccountClient,
    private val token: String,
    private val client: PlexClient,
    private val serverName: String,
    parentScope: CoroutineScope,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var guids by mutableStateOf(emptySet<String>())
    var titles by mutableStateOf<List<WatchlistTitle>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val details = MovieDetailsLoader(client, scope)

    val missing: Int get() = titles.count { !it.onServer }

    fun contains(guid: String): Boolean = guid in guids

    fun refresh() {
        job?.cancel()
        loading = titles.isEmpty()
        job = scope.launch {
            try {
                val entries = accountClient.watchlist(token)
                val found = entries.groupBy { it.type }
                    .flatMap { (type, list) -> client.itemsByGuid(type, list.map { it.guid }).entries }
                    .associate { it.key to it.value }
                titles = entries.map { entry ->
                    found[entry.guid]?.let { WatchlistTitle(it, onServer = true) } ?: WatchlistTitle(standIn(entry), onServer = false)
                }
                guids = entries.mapTo(HashSet()) { it.guid }
                error = null
                Analytics.event(
                    "watchlist", "entries" to entries.size, "found" to found.size,
                    "posterHosts" to entries.mapNotNull { host(it.thumb) }.distinct().joinToString(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Plex could not load your watchlist."
                Analytics.event("watchlist", "error" to error)
            }
            loading = false
        }
    }

    /** The button answers at once; a failed request puts it back and says why. */
    fun toggle(item: PlexBrowseItem) {
        val guid = item.guid
        val add = guid !in guids
        guids = if (add) guids + guid else guids - guid
        scope.launch {
            try {
                accountClient.setWatchlisted(token, guid, add)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                guids = if (add) guids - guid else guids + guid
                error = failure.message ?: "Plex could not update your watchlist."
            }
        }
    }

    fun close() {
        lifetime.cancel()
    }

    private fun standIn(entry: WatchlistEntry) = PlexBrowseItem(
        ratingKey = entry.guid,
        title = entry.title,
        subtitle = listOf(entry.year, "Not on $serverName").filter { it.isNotBlank() }.joinToString(" · "),
        // Hosted images reach the screen only through this server's image proxy.
        thumbPath = entry.thumb.takeIf(PlexSourceMetadata::isLogoPath),
        summary = entry.summary,
        type = entry.type,
        guid = entry.guid,
    )

    private fun host(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }
}

/** The watchlist of the connected server's account, for the details page's watchlist button. */
internal val LocalWatchlist = staticCompositionLocalOf<WatchlistState?> { null }

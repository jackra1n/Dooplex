package com.jackra1n.dooplex.watchlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackra1n.dooplex.details.MovieDetailsLoader
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.plex.PlexAccountClient
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The account's Plex watchlist, limited to the titles this server has, since only those can play
 * here. Titles are matched by their `plex://` id; the rest are only counted.
 */
class WatchlistState(
    private val accountClient: PlexAccountClient,
    private val token: String,
    private val client: PlexClient,
    parentScope: CoroutineScope,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private var job: Job? = null
    var items by mutableStateOf<List<PlexBrowseItem>>(emptyList())
        private set
    var missing by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val details = MovieDetailsLoader(client, scope)

    fun refresh() {
        job?.cancel()
        loading = items.isEmpty()
        job = scope.launch {
            try {
                val entries = accountClient.watchlist(token)
                val found = entries.groupBy { it.type }
                    .flatMap { (type, list) -> client.itemsByGuid(type, list.map { it.guid }).entries }
                    .associate { it.key to it.value }
                items = entries.mapNotNull { found[it.guid] }
                missing = entries.size - items.size
                error = null
                Analytics.event("watchlist", "entries" to entries.size, "found" to items.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Plex could not load your watchlist."
                Analytics.event("watchlist", "error" to error)
            }
            loading = false
        }
    }

    fun close() {
        lifetime.cancel()
    }
}

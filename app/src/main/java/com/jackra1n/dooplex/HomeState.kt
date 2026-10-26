package com.jackra1n.dooplex

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main-thread Home model: the server's shelves, a hero pool drawn from its recently added shelves,
 * and a details page opened from either. A hero title gains its clear logo and full synopsis from
 * one details fetch, the first time it is shown.
 */
@Stable
class HomeState(val connection: PlexConnection, parentScope: CoroutineScope) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private val client = PlexClient(connection)
    private val detailed = HashMap<String, PlexBrowseItem>()
    private var loadJob: Job? = null
    private var detailsJob: Job? = null

    var rows by mutableStateOf<List<PlexHomeRow>>(emptyList())
        private set
    var heroes by mutableStateOf<List<PlexBrowseItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedMovie by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var detailsLoading by mutableStateOf(false)
        private set
    var detailsError by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val loaded = Analytics.timed("home.load.ms") { client.homeRows() }
                rows = loaded
                heroes = heroPool(loaded).map { detailed[it.ratingKey] ?: it }
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Home could not be loaded."
            }
            loading = false
        }
    }

    suspend fun heroDetails(item: PlexBrowseItem): PlexBrowseItem {
        detailed[item.ratingKey]?.let { return it }
        val details = try {
            when (item.type) {
                "movie" -> client.movieDetails(item.ratingKey)
                "show" -> client.showMetadata(item.ratingKey)
                else -> item
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            item
        }
        detailed[item.ratingKey] = details
        heroes = heroes.map { if (it.ratingKey == item.ratingKey) details else it }
        return details
    }

    fun selectMovie(movie: PlexBrowseItem) {
        detailsJob?.cancel()
        selectedMovie = detailed[movie.ratingKey] ?: movie
        detailsLoading = true
        detailsError = null
        detailsJob = scope.launch {
            try {
                selectedMovie = client.movieDetails(movie.ratingKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                detailsError = failure.message ?: "Plex could not load these details."
            }
            detailsLoading = false
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        selectedMovie = null
        detailsLoading = false
        detailsError = null
    }

    fun close() {
        lifetime.cancel()
    }

    private companion object {
        const val HERO_COUNT = 8

        fun heroPool(rows: List<PlexHomeRow>): List<PlexBrowseItem> =
            (rows.filter { "recent" in it.key.lowercase() } + rows).asSequence()
                .flatMap { it.items }
                .filter { it.artPath != null && (it.type == "movie" || it.type == "show") }
                .distinctBy { it.ratingKey }
                .take(HERO_COUNT)
                .toList()
    }
}

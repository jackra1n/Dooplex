package com.jackra1n.dooplex.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jackra1n.dooplex.details.MovieDetailsLoader
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexConnection
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One show: its details, the episode Plex would play next, its seasons, and the chosen season's
 * episodes. It opens on [seasonNumber], else the next episode's season, else the first unwatched one.
 */
@Stable
internal class ShowPageState(
    show: PlexBrowseItem,
    private val connection: PlexConnection,
    parentScope: CoroutineScope,
    private var seasonNumber: Int? = null,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private val client = PlexClient(connection)
    private var job: Job? = null
    var show by mutableStateOf(show)
        private set
    var next by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val seasons = ShowBrowseState("/library/metadata/${show.ratingKey}/children", show.title, ShowBrowseLevel.SEASONS, connection, scope)
    var season by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var episodes by mutableStateOf<ShowBrowseState?>(null)
        private set
    val details = MovieDetailsLoader(client, scope)

    /** Plex lists a single-season show's episodes directly when it hides seasons. */
    val flat: Boolean get() = seasons.itemAt(0)?.type == "episode"

    val seasonList: List<PlexBrowseItem> get() = (0 until seasons.total).mapNotNull(seasons::itemAt)

    init {
        load()
    }

    fun selectSeason(item: PlexBrowseItem) {
        if (item.ratingKey == season?.ratingKey) return
        val path = item.childrenPath ?: return
        episodes?.close()
        season = item
        seasonNumber = item.index
        episodes = ShowBrowseState(path, item.title, ShowBrowseLevel.EPISODES, connection, scope)
    }

    /** After playback or a watched change: what to play next and every watched mark may differ. */
    fun refresh() {
        seasons.refresh()
        episodes?.takeIf { !flat }?.refresh()
        load()
    }

    fun close() {
        lifetime.cancel()
        seasons.close()
        episodes?.close()
    }

    private fun load() {
        job?.cancel()
        job = scope.launch {
            try {
                val (loaded, deck) = client.showDetails(show.ratingKey)
                show = loaded
                next = deck
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Plex could not load this show."
            }
            snapshotFlow { seasons.loading }.first { !it }
            if (season == null && episodes == null) openFirstSeason()
        }
    }

    private fun openFirstSeason() {
        if (flat) {
            episodes = seasons
            return
        }
        val list = seasonList
        val wanted = seasonNumber ?: next?.parentIndex
        (list.firstOrNull { it.index == wanted } ?: list.firstOrNull { !it.watched && it.index > 0 } ?: list.firstOrNull())
            ?.let(::selectSeason)
    }
}

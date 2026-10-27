package com.jackra1n.dooplex

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.details.MediaInfoState
import com.jackra1n.dooplex.home.HomeState
import com.jackra1n.dooplex.library.CollectionLibraryState
import com.jackra1n.dooplex.library.ShowBrowseLevel
import com.jackra1n.dooplex.library.ShowBrowseState
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexConnection
import com.jackra1n.dooplex.plex.PlexLibrary
import com.jackra1n.dooplex.plex.PlexNavigationSelection
import com.jackra1n.dooplex.plex.PlexNavigationStore
import com.jackra1n.dooplex.plex.PlexServer
import com.jackra1n.dooplex.search.SearchState
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.PageDip
import com.jackra1n.dooplex.watchlist.WatchlistState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One connected server: its tabs, the state behind each, and moving between them. Each tab's state
 * outlives its screen, so returning to a tab finds its titles and position without fetching again.
 */
@Stable
internal class Browser(
    val server: PlexServer,
    val connection: PlexConnection,
    val libraries: List<PlexLibrary>,
    savedSelection: PlexNavigationSelection,
    private val navigationStore: PlexNavigationStore,
    /** Loaded on connect, so a details page knows whether its title is on the watchlist. */
    val watchlist: WatchlistState,
    val posters: PosterRepository,
    private val scope: CoroutineScope,
    private val dip: PageDip,
    private val blocked: () -> Boolean,
    private val onNavigate: () -> Unit,
) {
    private val client = PlexClient(connection)
    private var selection by mutableStateOf(savedSelection)
    val focus = CollectionFocusState()
    var tab by mutableStateOf("home")
        private set
    var home by mutableStateOf(HomeState(connection, scope))
        private set
    var homeEpoch by mutableIntStateOf(0)
        private set
    var homeReady by mutableStateOf(false)
    var movies by mutableStateOf(collection("movie"))
        private set
    var shows by mutableStateOf(collection("show"))
        private set
    val showHistory = mutableStateListOf<ShowBrowseState>()
    var search by mutableStateOf<SearchState?>(null)
        private set
    var menu by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var mediaInfo by mutableStateOf<MediaInfoState?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private var homeOrigin = false
    private var selectedShowKey: String? = null
    private var pendingMovie: String? = null
    private var pendingShow: ShowBrowseState? = null

    init {
        watchlist.refresh()
    }

    /** The open season or episode list, when the TV Shows tab shows one. */
    val child: ShowBrowseState? get() = showHistory.lastOrNull().takeIf { tab == "show" }

    /** The remembered library of a type, or the server's first one; the library page can switch it. */
    fun libraryOf(type: String): PlexLibrary? =
        selection.libraryFor(libraries, type) ?: libraries.firstOrNull { it.type == type }

    /** Labels come from resolved libraries, never from type names; routes remain keyed by IDs. */
    fun label(type: String): String? = (if (type == "movie") movies else shows)?.library?.title ?: libraryOf(type)?.title

    fun showHome() {
        if (blocked()) return
        dip.change(::openHome)
    }

    /** Back from a library returns to Home, the same as choosing the Home tab. */
    fun goHome() {
        focus.enterContent("home")
        showHome()
    }

    /** Search and Watchlist are peers of Home: their tab shows them, and Back returns to Home. */
    fun openPage(name: String) {
        if (blocked()) return
        if (name == "search" && search == null) search = SearchState(client, scope)
        if (name == "watchlist") watchlist.refresh()
        dip.change { show(name) }
    }

    fun selectCollection(type: String) {
        if (blocked() || tab == type) return
        selectLibrary(libraryOf(type) ?: return)
    }

    fun selectHeaderLibrary(library: PlexLibrary) {
        if (blocked()) return
        val child = showHistory.lastOrNull().takeIf { tab == "show" && shows?.library?.key == library.key }
        focus.enterHeader(child?.let { "show:${it.path}" } ?: "library:${library.key}")
        selectLibrary(library)
    }

    /** Shows and seasons open in their own TV library; Back from that stack returns to Home. */
    fun openFromHome(item: PlexBrowseItem) {
        if (blocked()) return
        val path = item.childrenPath ?: return
        val library = libraries.firstOrNull { it.type == "show" && it.key == item.libraryKey } ?: libraryOf("show") ?: return
        dip.change {
            if (shows?.library?.key != library.key) {
                closeShows()
                shows = CollectionLibraryState(library, connection, scope)
            }
            clearShowHistory()
            if (item.type == "show") selectedShowKey = item.ratingKey
            val level = if (item.type == "season") ShowBrowseLevel.EPISODES else ShowBrowseLevel.SEASONS
            showHistory += ShowBrowseState(path, item.title, level, connection, scope)
            homeOrigin = true
            show("show")
        }
    }

    /** A shared root card opens the child-only stack; it never opens movie details. */
    fun openShow(item: PlexBrowseItem) {
        if (blocked() || tab != "show") return
        val path = item.childrenPath ?: return
        dip.change {
            clearShowHistory()
            selectedShowKey = item.ratingKey
            showHistory += ShowBrowseState(path, item.title, ShowBrowseLevel.SEASONS, connection, scope)
        }
    }

    /** The show library's grid is the root, so only a season list opens another level. */
    fun openSeason(item: PlexBrowseItem) {
        if (blocked() || showHistory.lastOrNull()?.level != ShowBrowseLevel.SEASONS) return
        val path = item.childrenPath ?: return
        dip.change { showHistory += ShowBrowseState(path, item.title, ShowBrowseLevel.EPISODES, connection, scope) }
    }

    /** Back inside a show steps up one level; anywhere else it returns to Home. */
    fun back() {
        if (blocked()) return
        if (tab != "show" || showHistory.isEmpty()) return goHome()
        dip.change {
            showHistory.removeLastOrNull()?.close()
            val parent = showHistory.lastOrNull()
            when {
                parent != null -> parent.refresh()
                homeOrigin -> openHome()
                else -> selectedShowKey?.let { shows?.refreshItem(it) }
            }
        }
    }

    fun openMenu(item: PlexBrowseItem) {
        if (!blocked()) menu = item
    }

    fun closeMenu() {
        menu = null
    }

    fun showMediaInfo(item: PlexBrowseItem) {
        menu = null
        mediaInfo = MediaInfoState(item, client, scope)
    }

    fun closeMediaInfo() {
        mediaInfo?.close()
        mediaInfo = null
    }

    fun toggleWatchlist(item: PlexBrowseItem) {
        menu = null
        watchlist.toggle(item)
    }

    fun setWatched(item: PlexBrowseItem, watched: Boolean) {
        menu = null
        scope.launch {
            try {
                client.setWatched(item.ratingKey, watched)
                refreshWatched(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                message = failure.message ?: "Plex could not update this title."
            }
        }
    }

    fun retry() {
        if (tab == "movie") movies?.retry() else showHistory.lastOrNull()?.retry() ?: shows?.retry()
    }

    /** Remembers what to refresh once the player closes. */
    fun played(item: PlexBrowseItem) {
        pendingMovie = item.ratingKey.takeIf { tab == "movie" }
        pendingShow = showHistory.lastOrNull().takeIf { tab == "show" }
    }

    fun refreshAfterPlayback() {
        pendingMovie?.let { movies?.refreshItem(it) }
        pendingShow?.let { show ->
            if (show in showHistory) show.refresh()
            selectedShowKey?.let { shows?.refreshItem(it) }
        }
        pendingMovie = null
        pendingShow = null
        home.refresh()
    }

    /** A fresh Home and libraries, as after a cold start. */
    fun restart() {
        closeStates()
        homeEpoch++
        homeReady = false
        home = HomeState(connection, scope)
        movies = collection("movie")
        shows = collection("show")
        openHome()
    }

    fun reopen(type: String) {
        val library = libraryOf(type) ?: return
        if (type == "movie") closeMovies() else closeShows()
        focus.enterContent(type)
        openLibrary(library)
    }

    fun close() {
        closeMediaInfo()
        closeStates()
        watchlist.close()
        posters.close()
    }

    private fun refreshWatched(item: PlexBrowseItem) {
        home.refresh()
        movies?.refreshItem(item.ratingKey)
        shows?.refreshItem(item.ratingKey)
        selectedShowKey?.let { shows?.refreshItem(it) }
        showHistory.lastOrNull()?.refresh()
        search?.let { it.type(it.query) }
        if (tab == "watchlist") watchlist.refresh()
    }

    private fun openHome() {
        homeOrigin = false
        show("home")
    }

    private fun show(name: String) {
        tab = name
        onNavigate()
    }

    /**
     * Switches at once instead of as a busy action, which would disable and re-enable every card.
     * The choice is saved in the background; a failed save only forgets it for the next start.
     */
    private fun selectLibrary(library: PlexLibrary) {
        dip.change { openLibrary(library) }
        scope.launch {
            try {
                saveSelection(library)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun saveSelection(library: PlexLibrary) {
        val key = if (library.type == "movie") selection.movieLibraryKey else selection.showLibraryKey
        if (selection.serverId != server.id || selection.activeType != library.type || key != library.key) {
            navigationStore.rememberLibrary(server.id, library)
            selection = navigationStore.load()
        }
    }

    private fun openLibrary(library: PlexLibrary) {
        if (library.type == "movie") {
            if (movies?.library?.key != library.key) {
                closeMovies()
                movies = CollectionLibraryState(library, connection, scope)
            }
        } else if (shows?.library?.key != library.key) {
            closeShows()
            shows = CollectionLibraryState(library, connection, scope)
        }
        homeOrigin = false
        show(library.type)
    }

    /** Saved libraries start loading with Home, so their first visit has titles ready. */
    private fun collection(type: String): CollectionLibraryState? =
        libraryOf(type)?.let { CollectionLibraryState(it, connection, scope) }

    private fun clearShowHistory() {
        showHistory.forEach { it.close() }
        showHistory.clear()
    }

    private fun closeMovies() {
        movies?.close()
        movies = null
        pendingMovie = null
    }

    private fun closeShows() {
        shows?.close()
        shows = null
        selectedShowKey = null
        clearShowHistory()
        pendingShow = null
    }

    private fun closeStates() {
        search?.close()
        search = null
        closeMovies()
        closeShows()
        home.close()
    }
}

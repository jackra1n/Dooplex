package com.jackra1n.dooplex

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackra1n.dooplex.ui.MenuRequest
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.details.MediaInfoState
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.home.HomeState
import com.jackra1n.dooplex.library.CollectionLibraryState
import com.jackra1n.dooplex.library.ShowPageState
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
    var showPage by mutableStateOf<ShowPageState?>(null)
        private set
    var search by mutableStateOf<SearchState?>(null)
        private set
    var menu by mutableStateOf<MenuRequest?>(null)
        private set
    var mediaInfo by mutableStateOf<MediaInfoState?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private var homeOrigin = false
    private var pendingMovie: String? = null
    private var pendingShow: ShowPageState? = null

    init {
        watchlist.refresh()
    }

    /** The open show, when the TV Shows tab shows one. */
    val page: ShowPageState? get() = showPage.takeIf { tab == "show" }

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
        focus.enterHeader("library:${library.key}")
        selectLibrary(library)
    }

    /** A show, season or episode opens its show's page in the TV library; Back returns to Home. */
    fun openFromHome(item: PlexBrowseItem) {
        if (blocked()) return
        val target = if (item.type == "show") item
            else PlexBrowseItem(ratingKey = item.showKey ?: return, title = item.showTitle, subtitle = "", type = "show")
        val season = when (item.type) {
            "season" -> item.index
            "episode" -> item.parentIndex
            else -> null
        }
        val library = libraries.firstOrNull { it.type == "show" && it.key == item.libraryKey } ?: libraryOf("show") ?: return
        dip.change {
            if (shows?.library?.key != library.key) {
                closeShows()
                shows = CollectionLibraryState(library, connection, scope)
            }
            openShowPage(target, season)
            homeOrigin = true
            show("show")
        }
    }

    fun openShow(item: PlexBrowseItem) {
        if (blocked() || tab != "show") return
        dip.change { openShowPage(item, null) }
    }

    /** Back from a show returns to where it was opened from; anywhere else it returns to Home. */
    fun back() {
        if (blocked()) return
        val page = showPage
        if (tab != "show" || page == null) return goHome()
        dip.change {
            closeShowPage()
            if (homeOrigin) openHome() else shows?.refreshItem(page.show.ratingKey)
        }
    }

    fun openMenu(request: MenuRequest) {
        if (blocked()) return
        menu = request
        Analytics.event("menu", "type" to request.item.type, "anchored" to (request.anchor != null))
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

    fun removeFromContinueWatching(item: PlexBrowseItem) {
        menu = null
        scope.launch {
            try {
                client.removeFromContinueWatching(item.ratingKey)
                home.leaveContinueWatching(item.ratingKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                message = failure.message ?: "Plex could not update Continue Watching."
            }
        }
    }

    fun retry() {
        if (tab == "movie") movies?.retry() else shows?.retry()
    }

    /** Remembers what to refresh once the player closes. */
    fun played(item: PlexBrowseItem) {
        pendingMovie = item.ratingKey.takeIf { tab == "movie" }
        pendingShow = page
    }

    fun refreshAfterPlayback() {
        pendingMovie?.let { movies?.refreshItem(it) }
        pendingShow?.let { page ->
            if (page == showPage) page.refresh()
            shows?.refreshItem(page.show.ratingKey)
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
        showPage?.let { page ->
            page.refresh()
            shows?.refreshItem(page.show.ratingKey)
        }
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

    private fun openShowPage(item: PlexBrowseItem, season: Int?) {
        closeShowPage()
        showPage = ShowPageState(item, connection, scope, season)
        homeOrigin = false
    }

    private fun closeShowPage() {
        showPage?.close()
        showPage = null
    }

    private fun closeMovies() {
        movies?.close()
        movies = null
        pendingMovie = null
    }

    private fun closeShows() {
        shows?.close()
        shows = null
        closeShowPage()
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

package com.jackra1n.dooplex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.graphics.ImageBitmap
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.home.HomeScreen
import com.jackra1n.dooplex.library.CollectionScreen
import com.jackra1n.dooplex.library.ShowsScreen
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.search.SearchScreen
import com.jackra1n.dooplex.ui.CollectionNavigationBar
import com.jackra1n.dooplex.ui.MessageToast
import com.jackra1n.dooplex.watchlist.WatchlistScreen

/** The current tab of a connected server, under the shared top bar. */
@Composable
internal fun BrowserScreen(
    browser: Browser,
    destinations: SaveableStateHolder,
    blocked: Boolean,
    error: String?,
    accountName: String,
    accountAvatar: ImageBitmap?,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
    onSwitchServer: () -> Unit,
    onSignOut: () -> Unit,
    onLeave: () -> Unit,
    onDismissError: () -> Unit,
) {
    val tab = browser.tab
    val focus = browser.focus
    val serverId = browser.server.id
    val collection = when (tab) {
        "movie" -> browser.movies
        "show" -> browser.shows
        else -> null
    }
    val child = browser.child.takeIf { collection != null }
    val route = when {
        child != null -> "show:${child.path}"
        collection != null -> "library:${collection.library.key}"
        else -> tab
    }
    val screen = when {
        tab == "home" -> if (browser.home.selectedMovie != null) "home/details" else "home"
        child != null -> "show/${child.level.name.lowercase()}"
        collection != null -> "library/$tab" + if (collection.selectedMovie != null) "/details" else ""
        else -> tab
    }
    LaunchedEffect(screen) {
        Analytics.screen = screen
        Analytics.mark("screen:$screen")
        Analytics.event("screen")
    }
    val navigation: @Composable () -> Unit = {
        CollectionNavigationBar(
            accountName = accountName,
            accountAvatar = accountAvatar,
            serverName = browser.server.name,
            activeType = tab,
            movieTitle = browser.label("movie"),
            showTitle = browser.label("show"),
            focus = focus,
            enabled = !blocked && browser.movies?.selectedMovie == null && browser.home.selectedMovie == null &&
                browser.search?.details?.movie == null && browser.watchlist?.details?.movie == null,
            onHome = browser::showHome,
            onMovies = { browser.selectCollection("movie") },
            onShows = { browser.selectCollection("show") },
            onWatchlist = { browser.openPage("watchlist") },
            onSearch = { browser.openPage("search") },
            onDown = { focus.enterHeader(route) },
            onSwitchServer = onSwitchServer,
            onSignOut = onSignOut,
        )
    }
    val search = browser.search
    val watchlist = browser.watchlist
    when {
        tab == "search" && search != null -> SearchScreen(
            state = search, posters = browser.posters, serverName = browser.server.name, navigation = navigation,
            focus = focus, enabled = !blocked,
            onPlay = onPlay, onOpen = browser::openFromHome, onBack = browser::goHome,
        )
        tab == "watchlist" && watchlist != null -> WatchlistScreen(
            state = watchlist, posters = browser.posters, serverName = browser.server.name, navigation = navigation,
            focus = focus, enabled = !blocked,
            onPlay = onPlay, onOpen = browser::openFromHome, onBack = browser::goHome,
        )
        tab == "home" -> key(browser.home) {
            destinations.SaveableStateProvider("$serverId:home:${browser.homeEpoch}") {
                HomeScreen(
                    state = browser.home,
                    posters = browser.posters,
                    navigation = navigation,
                    focus = focus,
                    enabled = !blocked,
                    onPlay = onPlay,
                    onOpen = browser::openFromHome,
                    onBack = onLeave,
                    onReady = {
                        browser.homeReady = true
                        Analytics.event("ready", "what" to "home")
                    },
                )
            }
        }
        child != null -> destinations.SaveableStateProvider("$serverId:show:${child.path}") {
            ShowsScreen(
                state = child,
                posters = browser.posters,
                enabled = !blocked,
                error = error,
                onBack = browser::back,
                onItem = { if (it.childrenPath == null) onPlay(it, false) else browser.openSeason(it) },
                navigation = navigation,
                focus = focus,
            )
        }
        collection != null -> key(collection) {
            destinations.SaveableStateProvider("$serverId:$tab:${collection.library.key}") {
                CollectionScreen(
                    state = collection,
                    posters = browser.posters,
                    navigation = navigation,
                    focus = focus,
                    libraries = browser.libraries.filter { it.type == tab },
                    onLibrary = browser::selectHeaderLibrary,
                    playbackRunning = blocked,
                    error = error,
                    onBack = browser::goHome,
                    onPlay = onPlay,
                    onOpenShow = browser::openShow,
                )
            }
        }
    }
    // These pages have no error area of their own, so failures there (starting playback) show briefly.
    if (collection == null) error?.let { MessageToast(it, onDismissError) }
}

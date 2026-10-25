package com.jackra1n.dooplex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jackra1n.dooplex.ui.theme.DooplexTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(PlexBrowserState(busy = true))
    private lateinit var store: PlexAccountStore
    private lateinit var accountClient: PlexAccountClient
    private var account: PlexAccount? = null
    private var connection: PlexConnection? = null
    private var movieLibrary by mutableStateOf<MovieLibraryState?>(null)
    private var posterRepository: PosterRepository? = null
    private var pendingMovie: String? = null
    private var actionJob: Job? = null
    private var actionVersion = 0
    private val history = mutableListOf<BrowseLocation>()

    private data class BrowseLocation(val path: String, val title: String, val start: Int = 0)

    private val nativePlayer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val position = try {
            ZidooPlayer.readResult(result.data)
        } catch (exception: IllegalStateException) {
            state = state.copy(error = exception.message)
            null
        }
        PlaybackService.finish(this, position)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlexAccountStore(this)
        accountClient = PlexAccountClient(store.clientId)
        setContent {
            val playback by PlaybackMonitor.state.collectAsState()
            DooplexTheme {
                val movies = movieLibrary
                val posters = posterRepository
                if (state.stage == BrowserStage.TITLES && movies != null && posters != null) {
                    MoviesScreen(
                        state = movies,
                        posters = posters,
                        accountName = state.accountName,
                        playbackRunning = playback.running || state.busy,
                        error = state.error,
                        onBack = ::navigateBack,
                        onPlay = { item, startFromBeginning -> play(item, startFromBeginning) },
                    )
                } else {
                    PlexBrowserScreen(
                        state = state,
                        playback = playback,
                        onLink = ::linkAccount,
                        onServer = ::selectServer,
                        onLibrary = ::selectLibrary,
                        onItem = ::selectItem,
                        onNextPage = { changePage(forward = true) },
                        onPreviousPage = { changePage(forward = false) },
                        onBack = ::navigateBack,
                        onDisconnect = ::disconnect,
                        onRetry = ::retry,
                    )
                }
            }
        }
        lifecycleScope.launch {
            PlaybackMonitor.state.collect { playback ->
                if (!playback.running && playback.phase != "Ready") {
                    val ratingKey = pendingMovie ?: return@collect
                    pendingMovie = null
                    movieLibrary?.refreshMovie(ratingKey)
                }
            }
        }
        runAction {
            val saved = store.load()
            if (saved == null) {
                state = PlexBrowserState()
            } else {
                account = accountClient.account(saved.token)
                showServers()
            }
        }
    }

    private fun linkAccount() {
        runAction {
            state = PlexBrowserState(busy = true)
            val pin = accountClient.createPin()
            state = state.copy(pin = pin, busy = false, canGoBack = true)
            while (System.currentTimeMillis() < pin.expiresAtMs) {
                delay(1_000)
                val token = accountClient.pollPin(pin) ?: continue
                val linked = accountClient.account(token)
                store.save(linked)
                account = linked
                state = state.copy(busy = true, pin = null, canGoBack = false)
                showServers()
                return@runAction
            }
            state = state.copy(error = "This code has expired. Get a new code to continue.")
        }
    }

    private suspend fun showServers() {
        val current = account ?: return
        state = PlexBrowserState(
            stage = BrowserStage.SERVERS,
            heading = "Choose your Plex server",
            accountName = current.displayName,
            busy = true,
        )
        val servers = accountClient.servers(current.token)
        state = state.copy(servers = servers)
    }

    private fun selectServer(server: PlexServer) {
        if (PlaybackMonitor.state.value.running) return
        runAction {
            val selected = accountClient.connect(server)
            val libraries = PlexClient(selected).libraries()
            closeMovies()
            posterRepository?.close()
            posterRepository = PosterRepository(this, selected)
            connection = selected
            history.clear()
            state = state.copy(
                stage = BrowserStage.LIBRARIES,
                heading = server.name,
                libraries = libraries,
                page = null,
                canGoBack = true,
            )
        }
    }

    private fun selectLibrary(library: PlexLibrary) {
        if (PlaybackMonitor.state.value.running) return
        val selected = connection ?: return
        closeMovies()
        history.clear()
        val location = BrowseLocation("/library/sections/${library.key}/all", library.title)
        history += location
        if (library.type == "movie") {
            cancelAction()
            state = state.copy(
                stage = BrowserStage.TITLES,
                heading = library.title,
                page = null,
                busy = false,
                error = null,
                canGoBack = true,
            )
            movieLibrary = MovieLibraryState(library, selected, lifecycleScope)
        } else {
            runAction { showPage(location) }
        }
    }

    private fun selectItem(item: PlexBrowseItem) {
        if (PlaybackMonitor.state.value.running) return
        if (item.childrenPath != null) {
            val location = BrowseLocation(item.childrenPath, item.title)
            history += location
            runAction { showPage(location) }
        } else {
            play(item)
        }
    }

    private suspend fun showPage(location: BrowseLocation) {
        val selected = connection ?: return
        state = state.copy(
            stage = BrowserStage.TITLES,
            heading = location.title,
            page = null,
            busy = true,
            canGoBack = true,
        )
        val page = PlexClient(selected).browse(location.path, location.start)
        state = state.copy(page = page)
    }

    private fun changePage(forward: Boolean) {
        val page = state.page ?: return
        val location = history.lastOrNull() ?: return
        if (forward && !page.hasNext || !forward && page.start == 0) return
        val start = if (forward) page.start + page.items.size else (page.start - 40).coerceAtLeast(0)
        val updated = location.copy(start = start)
        history[history.lastIndex] = updated
        runAction { showPage(updated) }
    }

    private fun navigateBack() {
        if (PlaybackMonitor.state.value.running) return
        when (state.stage) {
            BrowserStage.SIGN_IN -> {
                cancelAction()
                state = PlexBrowserState()
            }
            BrowserStage.SERVERS -> Unit
            BrowserStage.LIBRARIES -> {
                connection = null
                posterRepository?.close()
                posterRepository = null
                history.clear()
                runAction { showServers() }
            }
            BrowserStage.TITLES -> {
                cancelAction()
                closeMovies()
                history.removeLastOrNull()
                val parent = history.lastOrNull()
                if (parent != null) {
                    runAction { showPage(parent) }
                } else {
                    state = state.copy(
                        stage = BrowserStage.LIBRARIES,
                        heading = "Choose a library",
                        page = null,
                        busy = false,
                        error = null,
                    )
                }
            }
        }
    }

    private fun retry() {
        when (state.stage) {
            BrowserStage.SIGN_IN -> linkAccount()
            BrowserStage.SERVERS -> runAction { showServers() }
            BrowserStage.LIBRARIES -> {
                val selected = connection ?: return
                runAction { state = state.copy(libraries = PlexClient(selected).libraries()) }
            }
            BrowserStage.TITLES -> {
                val current = history.lastOrNull() ?: return
                runAction { showPage(current) }
            }
        }
    }

    private fun disconnect() {
        if (PlaybackMonitor.state.value.running) return
        runAction {
            closeMovies()
            posterRepository?.close()
            posterRepository = null
            account = null
            connection = null
            history.clear()
            state = PlexBrowserState(busy = true)
            store.clear()
            state = PlexBrowserState()
        }
    }

    private fun play(item: PlexBrowseItem, startFromBeginning: Boolean = false) {
        val selected = connection ?: return
        runAction {
            val original = PlexClient(selected).loadMedia(item.ratingKey)
            val media = if (startFromBeginning) original.copy(resumePositionMs = 0) else original
            val intent = ZidooPlayer(this).createIntent(media)
            pendingMovie = item.ratingKey.takeIf { movieLibrary != null }
            PlaybackService.begin(this, selected, media)
            try {
                nativePlayer.launch(intent)
            } catch (_: Exception) {
                PlaybackService.finish(this, null)
                throw IllegalStateException("Android could not launch Zidoo's native player.")
            }
        }
    }

    private fun closeMovies() {
        movieLibrary?.close()
        movieLibrary = null
        pendingMovie = null
    }

    override fun onDestroy() {
        closeMovies()
        posterRepository?.close()
        posterRepository = null
        super.onDestroy()
    }

    private fun cancelAction() {
        actionVersion++
        actionJob?.cancel()
        actionJob = null
    }

    private fun runAction(block: suspend () -> Unit) {
        cancelAction()
        val version = actionVersion
        state = state.copy(busy = true, error = null)
        actionJob = lifecycleScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: PlexAuthenticationException) {
                account = null
                closeMovies()
                posterRepository?.close()
                posterRepository = null
                connection = null
                history.clear()
                val message = try {
                    store.clear()
                    "Your Plex sign-in expired. Link your account again."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "Your Plex sign-in expired, and the saved sign-in could not be removed."
                }
                state = PlexBrowserState(error = message)
            } catch (exception: Exception) {
                state = state.copy(error = exception.message ?: "Plex could not complete this request.")
            } finally {
                if (version == actionVersion) state = state.copy(busy = false)
            }
        }
    }
}
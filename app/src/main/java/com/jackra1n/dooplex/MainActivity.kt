package com.jackra1n.dooplex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    private lateinit var navigationStore: PlexNavigationStore
    private lateinit var accountClient: PlexAccountClient
    private var account: PlexAccount? = null
    private var connection: PlexConnection? = null
    private var movieLibrary by mutableStateOf<CollectionLibraryState?>(null)
    private var showLibrary by mutableStateOf<CollectionLibraryState?>(null)
    private var selectedShowKey: String? = null
    private var activeLibrary by mutableStateOf<PlexLibrary?>(null)
    private val showHistory = mutableStateListOf<ShowBrowseState>()
    private var posterRepository: PosterRepository? = null
    private var serverId: String? = null
    private var serverName = ""
    private var navigationSelection = PlexNavigationSelection()
    private var pendingMovie: String? = null
    private var pendingShow: ShowBrowseState? = null
    private var actionJob: Job? = null
    private var actionVersion = 0

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
        navigationStore = PlexNavigationStore(this)
        accountClient = PlexAccountClient(store.clientId)
        setContent {
            val playback by PlaybackMonitor.state.collectAsState()
            val destinations = rememberSaveableStateHolder()
            DooplexTheme {
                val library = activeLibrary
                val posters = posterRepository
                if (state.stage == BrowserStage.TITLES && library != null && posters != null) {
                    val blocked = playback.running || state.busy
                    val navigation: @Composable () -> Unit = {
                        CollectionNavigationBar(
                            accountName = state.accountName,
                            activeType = library.type,
                            hasMovies = state.libraries.any { it.type == "movie" },
                            hasShows = state.libraries.any { it.type == "show" },
                            enabled = !blocked && movieLibrary?.selectedMovie == null,
                            onMovies = { selectCollection("movie") },
                            onShows = { selectCollection("show") },
                        )
                    }
                    val child = showHistory.lastOrNull().takeIf { library.type == "show" }
                    if (child == null) {
                        val collection = checkNotNull(if (library.type == "movie") movieLibrary else showLibrary)
                        destinations.SaveableStateProvider("$serverId:${library.type}:${library.key}") {
                            CollectionScreen(
                                state = collection,
                                posters = posters,
                                navigation = navigation,
                                playbackRunning = blocked,
                                error = state.error,
                                onBack = ::showSources,
                                onPlay = { item, startFromBeginning -> play(item, startFromBeginning) },
                                onOpenShow = ::openShow,
                            )
                        }
                    } else {
                        destinations.SaveableStateProvider("$serverId:show:${child.path}") {
                            ShowsScreen(
                                state = child,
                                posters = posters,
                                enabled = !blocked,
                                error = state.error,
                                onBack = ::navigateBack,
                                onItem = ::selectShowItem,
                                navigation = navigation,
                            )
                        }
                    }
                } else {
                    PlexBrowserScreen(
                        state = state,
                        playback = playback,
                        onLink = ::linkAccount,
                        onServer = ::selectServer,
                        onLibrary = ::selectLibrary,
                        onServers = ::showServerPicker,
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
                    pendingMovie?.let { movieLibrary?.refreshItem(it) }
                    pendingMovie = null
                    pendingShow?.takeIf { it in showHistory }?.refresh()
                    if (pendingShow != null) selectedShowKey?.let { showLibrary?.refreshItem(it) }
                    pendingShow = null
                }
            }
        }
        runAction {
            val saved = store.load()
            if (saved == null) {
                state = PlexBrowserState()
            } else {
                account = saved
                navigationSelection = navigationStore.load()
                showServers(restore = true)
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
                navigationStore.clear()
                navigationSelection = PlexNavigationSelection()
                account = linked
                state = state.copy(busy = true, pin = null, canGoBack = false)
                showServers(restore = true)
                return@runAction
            }
            state = state.copy(error = "This code has expired. Get a new code to continue.")
        }
    }

    /** Restore IDs only after resolving the account's currently authorized server and libraries. */
    private suspend fun showServers(restore: Boolean = false) {
        val current = account ?: return
        state = PlexBrowserState(
            stage = BrowserStage.SERVERS,
            heading = if (restore) "Opening your collection" else "Choose your Plex server",
            accountName = current.displayName,
            libraries = state.libraries,
            busy = true,
            canGoBack = connection != null,
        )
        val servers = accountClient.servers(current.token)
        state = state.copy(servers = servers)
        if (restore) {
            val server = navigationSelection.serverFrom(servers)
            if (server != null) connectServer(server)
            else if (navigationSelection.serverId != null)
                state = state.copy(error = "Your saved server is not available. Choose a server to continue.")
        }
    }

    private fun showServerPicker() {
        if (PlaybackMonitor.state.value.running || state.busy) return
        runAction { showServers() }
    }

    private fun selectServer(server: PlexServer) {
        if (PlaybackMonitor.state.value.running || state.busy) return
        runAction { connectServer(server) }
    }

    private suspend fun connectServer(server: PlexServer) {
        val selected = accountClient.connect(server)
        val libraries = PlexClient(selected).libraries()
        closeCollections()
        posterRepository?.close()
        posterRepository = PosterRepository(this, selected)
        connection = selected
        serverId = server.id
        serverName = server.name
        state = state.copy(
            stage = BrowserStage.LIBRARIES,
            heading = "Libraries · ${server.name}",
            libraries = libraries,
            canGoBack = true,
        )
        if (navigationSelection.serverId != server.id) {
            navigationStore.rememberServer(server.id)
            navigationSelection = navigationStore.load()
        }
        val preferred = navigationSelection.preferredLibrary(libraries)
        if (preferred != null) {
            saveLibrarySelection(preferred)
            openLibrary(preferred)
        } else if (navigationSelection.movieLibraryKey != null || navigationSelection.showLibraryKey != null) {
            state = state.copy(error = "Choose an available library to continue.")
        }
    }

    private suspend fun saveLibrarySelection(library: PlexLibrary) {
        val id = checkNotNull(serverId)
        val key = if (library.type == "movie") navigationSelection.movieLibraryKey else navigationSelection.showLibraryKey
        if (navigationSelection.serverId != id || navigationSelection.activeType != library.type || key != library.key) {
            navigationStore.rememberLibrary(id, library)
            navigationSelection = navigationStore.load()
        }
    }

    private fun selectLibrary(library: PlexLibrary) {
        if (PlaybackMonitor.state.value.running || state.busy || connection == null) return
        runAction {
            saveLibrarySelection(library)
            openLibrary(library)
        }
    }

    private fun openLibrary(library: PlexLibrary) {
        val selected = checkNotNull(connection)
        if (library.type == "movie") {
            if (movieLibrary?.library?.key != library.key) {
                closeMovies()
                movieLibrary = CollectionLibraryState(library, selected, lifecycleScope)
            }
        } else {
            if (showLibrary?.library?.key != library.key) {
                closeShows()
                showLibrary = CollectionLibraryState(library, selected, lifecycleScope)
            }
        }
        activeLibrary = library
        state = state.copy(stage = BrowserStage.TITLES, heading = library.title, error = null, canGoBack = true)
    }

    private fun selectCollection(type: String) {
        if (PlaybackMonitor.state.value.running || state.busy || activeLibrary?.type == type) return
        val library = navigationSelection.libraryFor(state.libraries, type)
        if (library != null) selectLibrary(library) else showSources()
    }

    /** A shared root card opens the child-only stack; it never opens movie details. */
    private fun openShow(item: PlexBrowseItem) {
        if (PlaybackMonitor.state.value.running || state.busy || activeLibrary?.type != "show") return
        val path = item.childrenPath ?: return
        val root = showLibrary ?: return
        showHistory.forEach { it.close() }
        showHistory.clear()
        selectedShowKey = item.ratingKey
        showHistory += ShowBrowseState(path, item.title, ShowBrowseLevel.SEASONS, root.connection, lifecycleScope)
    }

    private fun selectShowItem(item: PlexBrowseItem) {
        if (PlaybackMonitor.state.value.running || state.busy) return
        val current = showHistory.lastOrNull() ?: return
        val path = item.childrenPath
        if (path == null) {
            play(item)
        } else {
            val next = when (current.level) {
                // The root is CollectionScreen, not a ShowBrowseState.
                ShowBrowseLevel.SEASONS -> ShowBrowseLevel.EPISODES
                ShowBrowseLevel.EPISODES -> return
            }
            showHistory += ShowBrowseState(path, item.title, next, current.connection, lifecycleScope)
        }
    }

    private fun showSources() {
        if (PlaybackMonitor.state.value.running || state.busy) return
        state = state.copy(stage = BrowserStage.LIBRARIES, heading = "Libraries · $serverName", error = null, canGoBack = true)
    }

    private fun resumeCollection() {
        val library = activeLibrary ?: return
        state = state.copy(stage = BrowserStage.TITLES, heading = library.title, busy = false, error = null, canGoBack = true)
    }

    private fun navigateBack() {
        if (PlaybackMonitor.state.value.running) return
        when (state.stage) {
            BrowserStage.SIGN_IN -> {
                cancelAction()
                state = PlexBrowserState()
            }
            BrowserStage.SERVERS -> {
                if (activeLibrary != null) { cancelAction(); resumeCollection() }
                else if (connection != null) { cancelAction(); state = state.copy(busy = false); showSources() }
            }
            BrowserStage.LIBRARIES -> {
                cancelAction()
                if (activeLibrary != null) resumeCollection()
                else { state = state.copy(busy = false); showServerPicker() }
            }
            BrowserStage.TITLES -> {
                if (activeLibrary?.type == "show" && showHistory.isNotEmpty()) {
                    showHistory.removeAt(showHistory.lastIndex).close()
                    val parent = showHistory.lastOrNull()
                    if (parent != null) parent.refresh()
                    else selectedShowKey?.let { showLibrary?.refreshItem(it) }
                } else showSources()
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
                if (activeLibrary?.type == "movie") movieLibrary?.retry()
                else showHistory.lastOrNull()?.retry() ?: showLibrary?.retry()
            }
        }
    }

    private fun disconnect() {
        if (PlaybackMonitor.state.value.running) return
        runAction {
            closeCollections()
            posterRepository?.close()
            posterRepository = null
            account = null
            connection = null
            serverId = null
            serverName = ""
            navigationSelection = PlexNavigationSelection()
            state = PlexBrowserState(busy = true)
            store.clear()
            navigationStore.clear()
            state = PlexBrowserState()
        }
    }

    private fun play(item: PlexBrowseItem, startFromBeginning: Boolean = false) {
        if (PlaybackMonitor.state.value.running || state.busy) return
        val selected = connection ?: return
        runAction {
            val original = PlexClient(selected).loadMedia(item.ratingKey)
            val media = if (startFromBeginning) original.copy(resumePositionMs = 0) else original
            val intent = ZidooPlayer(this).createIntent(media)
            pendingMovie = item.ratingKey.takeIf { activeLibrary?.type == "movie" }
            pendingShow = showHistory.lastOrNull().takeIf { activeLibrary?.type == "show" }
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

    private fun closeShows() {
        showLibrary?.close()
        showLibrary = null
        selectedShowKey = null
        showHistory.forEach { it.close() }
        showHistory.clear()
        pendingShow = null
    }

    private fun closeCollections() {
        closeMovies()
        closeShows()
        activeLibrary = null
    }

    override fun onDestroy() {
        closeCollections()
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
                closeCollections()
                posterRepository?.close()
                posterRepository = null
                connection = null
                serverId = null
                serverName = ""
                navigationSelection = PlexNavigationSelection()
                val message = try {
                    store.clear()
                    navigationStore.clear()
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
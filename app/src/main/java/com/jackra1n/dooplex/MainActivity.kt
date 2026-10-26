package com.jackra1n.dooplex

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
    private var homeState by mutableStateOf<HomeState?>(null)
    private var showingHome by mutableStateOf(false)
    private var homeOrigin = false
    private var splash by mutableStateOf(true)
    private var homeReady by mutableStateOf(false)
    private var homeEpoch by mutableIntStateOf(0)
    private val showHistory = mutableStateListOf<ShowBrowseState>()
    private var posterRepository: PosterRepository? = null
    private var serverId: String? = null
    private var serverName = ""
    private var navigationSelection by mutableStateOf(PlexNavigationSelection())
    private val collectionFocus = CollectionFocusState()
    private var choosingLibraryType by mutableStateOf<String?>(null)
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
        Analytics.start(this)
        Analytics.onFlagsChanged = { posterRepository?.clearMemory() }
        Analytics.onCommand = ::runCommand
        // The Compose surface covers the whole window, so the window's own fill is wasted GPU work.
        window.setBackgroundDrawable(null)
        store = PlexAccountStore(this)
        navigationStore = PlexNavigationStore(this)
        accountClient = PlexAccountClient(store.clientId)
        setContent {
            val playback by PlaybackMonitor.state.collectAsState()
            val destinations = rememberSaveableStateHolder()
            DooplexTheme {
                val library = activeLibrary
                val posters = posterRepository
                val home = homeState.takeIf { showingHome }
                if (state.stage == BrowserStage.TITLES && posters != null && (home != null || library != null)) {
                    val blocked = playback.running || state.busy
                    val child = showHistory.lastOrNull().takeIf { home == null && library?.type == "show" }
                    val route = when {
                        home != null -> "home"
                        child != null -> "show:${child.path}"
                        else -> "library:${library?.key}"
                    }
                    val screen = when {
                        home != null -> if (home.selectedMovie != null) "home/details" else "home"
                        child != null -> "show/${child.level.name.lowercase()}"
                        library != null -> "library/${library.type}" + if (movieLibrary?.selectedMovie != null && library.type == "movie") "/details" else ""
                        else -> "titles"
                    }
                    LaunchedEffect(screen) {
                        Analytics.screen = screen
                        Analytics.mark("screen:$screen")
                        Analytics.event("screen")
                    }
                    val navigation: @Composable () -> Unit = {
                        CollectionNavigationBar(
                            accountName = state.accountName,
                            activeType = if (home != null) "home" else library?.type.orEmpty(),
                            movieTitle = collectionLabel("movie"),
                            showTitle = collectionLabel("show"),
                            focus = collectionFocus,
                            enabled = !blocked && movieLibrary?.selectedMovie == null && homeState?.selectedMovie == null,
                            onHome = ::showHome,
                            onMovies = { selectCollection("movie") },
                            onShows = { selectCollection("show") },
                            onDown = { collectionFocus.enterHeader(route) },
                        )
                    }
                    val shown = when {
                        home != null -> "home"
                        child != null -> "child"
                        else -> library?.type
                    }
                    homeState?.let { kept ->
                        key(kept) {
                            KeptScreen(shown == "home") {
                                destinations.SaveableStateProvider("$serverId:home:$homeEpoch") {
                                    HomeScreen(
                                        state = kept,
                                        posters = posters,
                                        navigation = navigation,
                                        focus = collectionFocus,
                                        enabled = !blocked,
                                        onPlay = { item, startFromBeginning -> play(item, startFromBeginning) },
                                        onOpen = ::openFromHome,
                                        onBack = ::showSources,
                                        onReady = {
                                            homeReady = true
                                            Analytics.event("ready", "what" to "home")
                                        },
                                    )
                                }
                            }
                        }
                    }
                    for (collection in listOfNotNull(movieLibrary, showLibrary)) key(collection) {
                        val type = collection.library.type
                        KeptScreen(shown == type) {
                            destinations.SaveableStateProvider("$serverId:$type:${collection.library.key}") {
                                CollectionScreen(
                                    state = collection,
                                    posters = posters,
                                    navigation = navigation,
                                    focus = collectionFocus,
                                    libraries = state.libraries.filter { it.type == type },
                                    onLibrary = ::selectHeaderLibrary,
                                    playbackRunning = blocked,
                                    error = state.error,
                                    onBack = ::showSources,
                                    onPlay = { item, startFromBeginning -> play(item, startFromBeginning) },
                                    onOpenShow = ::openShow,
                                )
                            }
                        }
                    }
                    if (library != null && child != null) {
                        destinations.SaveableStateProvider("$serverId:show:${child.path}") {
                            ShowsScreen(
                                state = child,
                                posters = posters,
                                enabled = !blocked,
                                error = state.error,
                                onBack = ::navigateBack,
                                onItem = ::selectShowItem,
                                navigation = navigation,
                                focus = collectionFocus,
                                library = library,
                                libraries = state.libraries.filter { it.type == library.type },
                                onLibrary = ::selectHeaderLibrary,
                            )
                        }
                    }
                    choosingLibraryType?.let { type ->
                        CollectionLibraryDialog(
                            libraries = state.libraries.filter { it.type == type },
                            selectedKey = navigationSelection.libraryFor(state.libraries, type)?.key,
                            onDismiss = {
                                choosingLibraryType = null
                                collectionFocus.moveTab(type)
                            },
                            onChoose = {
                                choosingLibraryType = null
                                collectionFocus.enterContent(type)
                                selectLibrary(it)
                            },
                        )
                    }
                } else {
                    LaunchedEffect(state.stage) {
                        Analytics.screen = state.stage.name.lowercase()
                        Analytics.event("screen")
                    }
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
                val waiting = state.busy || state.stage == BrowserStage.TITLES && showingHome && !homeReady
                LaunchedEffect(splash, waiting, state.error) {
                    if (splash && (!waiting || state.error != null)) splash = false
                }
                LaunchedEffect(splash) {
                    if (splash) {
                        delay(SPLASH_LIMIT_MS)
                        splash = false
                    }
                }
                val splashAlpha by animateFloatAsState(if (splash) 1f else 0f, tween(400), label = "start screen")
                if (splashAlpha > 0f) StartScreen(Modifier.fillMaxSize().graphicsLayer { alpha = splashAlpha })
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
                    homeState?.refresh()
                }
            }
        }
        runAction {
            val saved = store.load()
            if (saved == null) {
                linkAccount()
            } else {
                account = saved
                navigationSelection = navigationStore.load()
                showServers(restore = true)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val key = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
            Analytics.mark("key:$key" + if (event.repeatCount > 0) "+held" else "")
            Analytics.event("key", "key" to key)
        }
        // Keys would move focus on the page hidden under the start screen.
        if (splash) return true
        return super.dispatchKeyEvent(event)
    }

    /** Commands from tools/bench.py, so every benchmark run starts from the same state. */
    private fun runCommand(command: String) {
        if (PlaybackMonitor.state.value.running || state.busy || connection == null) return
        when (command) {
            "clear-memory", "clear-disk" -> {
                posterRepository?.clearMemory()
                if (command == "clear-disk") posterRepository?.clearDisk()
                clearWashes()
            }
            "home" -> restartHome()
            "open:movie", "open:show" -> reopenLibrary(command.removePrefix("open:"))
            "focus" -> collectionFocus.enterContent(if (showingHome) "home" else activeLibrary?.type ?: return)
            "tab:home" -> {
                collectionFocus.enterContent("home")
                showHome()
            }
            "tab:movie", "tab:show" -> {
                collectionFocus.enterContent(command.removePrefix("tab:"))
                selectCollection(command.removePrefix("tab:"))
            }
        }
    }

    /** A fresh Home behind the start screen, as after a cold start. */
    private fun restartHome() {
        val selected = connection ?: return
        closeCollections()
        homeEpoch++
        homeReady = false
        splash = true
        homeState = HomeState(selected, lifecycleScope)
        prepareLibraries(selected)
        openHome()
    }

    private fun reopenLibrary(type: String) {
        val library = navigationSelection.libraryFor(state.libraries, type) ?: return
        if (type == "movie") closeMovies() else closeShows()
        collectionFocus.enterContent(type)
        openLibrary(library)
    }

    /** Codes renew on their own a few times, so an idle sign-in screen stops polling plex.tv eventually. */
    private fun linkAccount() {
        runAction {
            state = PlexBrowserState(busy = true)
            repeat(PIN_GENERATIONS) {
                val pin = accountClient.createPin()
                val qr = try {
                    accountClient.pinQr(pin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                state = state.copy(pin = pin, pinQr = qr, busy = false)
                while (System.currentTimeMillis() < pin.expiresAtMs) {
                    delay(1_000)
                    val token = accountClient.pollPin(pin) ?: continue
                    val linked = accountClient.account(token)
                    store.save(linked)
                    navigationStore.clear()
                    navigationSelection = PlexNavigationSelection()
                    account = linked
                    splash = true
                    state = state.copy(busy = true, pin = null, pinQr = null)
                    showServers(restore = true)
                    return@runAction
                }
            }
            state = state.copy(pin = null, pinQr = null, error = "The sign-in code expired. Choose New code to try again.")
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
        homeReady = false
        homeState = HomeState(selected, lifecycleScope)
        prepareLibraries(selected)
        openHome()
    }

    /** The saved libraries are built hidden behind the start screen, so even the first switch is instant. */
    private fun prepareLibraries(selected: PlexConnection) {
        navigationSelection.libraryFor(state.libraries, "movie")?.let {
            movieLibrary = CollectionLibraryState(it, selected, lifecycleScope)
        }
        navigationSelection.libraryFor(state.libraries, "show")?.let {
            showLibrary = CollectionLibraryState(it, selected, lifecycleScope)
        }
    }

    private fun openHome() {
        homeOrigin = false
        showingHome = true
        state = state.copy(stage = BrowserStage.TITLES, heading = "Home", error = null, canGoBack = true)
    }

    private fun showHome() {
        if (PlaybackMonitor.state.value.running || state.busy || homeState == null) return
        openHome()
    }

    /** Shows and seasons open in their own TV library; Back from that stack returns to Home. */
    private fun openFromHome(item: PlexBrowseItem) {
        if (PlaybackMonitor.state.value.running || state.busy) return
        val selected = connection ?: return
        val path = item.childrenPath ?: return
        val library = state.libraries.firstOrNull { it.type == "show" && it.key == item.libraryKey }
            ?: navigationSelection.libraryFor(state.libraries, "show") ?: return
        if (showLibrary?.library?.key != library.key) {
            closeShows()
            showLibrary = CollectionLibraryState(library, selected, lifecycleScope)
        }
        showHistory.forEach { it.close() }
        showHistory.clear()
        if (item.type == "show") selectedShowKey = item.ratingKey
        val level = if (item.type == "season") ShowBrowseLevel.EPISODES else ShowBrowseLevel.SEASONS
        showHistory += ShowBrowseState(path, item.title, level, selected, lifecycleScope)
        activeLibrary = library
        showingHome = false
        homeOrigin = true
        state = state.copy(stage = BrowserStage.TITLES, heading = library.title, error = null, canGoBack = true)
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
        showingHome = false
        homeOrigin = false
        state = state.copy(stage = BrowserStage.TITLES, heading = library.title, error = null, canGoBack = true)
    }

    /** Labels come from resolved libraries, never from type names; routes remain keyed by IDs. */
    private fun collectionLabel(type: String): String? =
        (if (type == "movie") movieLibrary?.library else showLibrary?.library)?.title
            ?: navigationSelection.libraryFor(state.libraries, type)?.title
            ?: state.libraries.firstOrNull { it.type == type }?.title

    private fun selectHeaderLibrary(library: PlexLibrary) {
        if (PlaybackMonitor.state.value.running || state.busy) return
        val child = showHistory.lastOrNull().takeIf {
            library.type == "show" && activeLibrary?.type == "show" && activeLibrary?.key == library.key
        }
        collectionFocus.enterHeader(child?.let { "show:${it.path}" } ?: "library:${library.key}")
        selectLibrary(library)
    }

    private fun selectCollection(type: String) {
        if (PlaybackMonitor.state.value.running || state.busy || !showingHome && activeLibrary?.type == type) return
        val library = navigationSelection.libraryFor(state.libraries, type)
        if (library != null) selectLibrary(library) else choosingLibraryType = type
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
        if (showingHome && homeState != null) return openHome()
        val library = activeLibrary ?: return
        state = state.copy(stage = BrowserStage.TITLES, heading = library.title, busy = false, error = null, canGoBack = true)
    }

    private fun navigateBack() {
        if (PlaybackMonitor.state.value.running) return
        when (state.stage) {
            BrowserStage.SIGN_IN -> finish()
            BrowserStage.SERVERS -> {
                if (activeLibrary != null || homeState != null) { cancelAction(); resumeCollection() }
                else if (connection != null) { cancelAction(); state = state.copy(busy = false); showSources() }
            }
            BrowserStage.LIBRARIES -> {
                cancelAction()
                if (activeLibrary != null || homeState != null) resumeCollection()
                else { state = state.copy(busy = false); showServerPicker() }
            }
            BrowserStage.TITLES -> {
                if (!showingHome && activeLibrary?.type == "show" && showHistory.isNotEmpty()) {
                    showHistory.removeAt(showHistory.lastIndex).close()
                    val parent = showHistory.lastOrNull()
                    if (parent != null) parent.refresh()
                    else if (homeOrigin && homeState != null) openHome()
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
            linkAccount()
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
        choosingLibraryType = null
        closeMovies()
        closeShows()
        activeLibrary = null
        homeState?.close()
        homeState = null
        showingHome = false
        homeOrigin = false
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

    private companion object {
        const val PIN_GENERATIONS = 4
        const val SPLASH_LIMIT_MS = 6_000L
    }
}

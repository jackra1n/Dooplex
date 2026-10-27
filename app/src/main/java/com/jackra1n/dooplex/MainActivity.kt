package com.jackra1n.dooplex

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.lifecycleScope
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.clearWashes
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.playback.PlaybackMonitor
import com.jackra1n.dooplex.playback.PlaybackService
import com.jackra1n.dooplex.playback.PlayerHandoff
import com.jackra1n.dooplex.plex.PlexAccount
import com.jackra1n.dooplex.plex.PlexAccountClient
import com.jackra1n.dooplex.plex.PlexAccountStore
import com.jackra1n.dooplex.plex.PlexAuthenticationException
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexNavigationStore
import com.jackra1n.dooplex.plex.PlexServer
import com.jackra1n.dooplex.setup.BrowserStage
import com.jackra1n.dooplex.setup.PlexBrowserScreen
import com.jackra1n.dooplex.setup.PlexBrowserState
import com.jackra1n.dooplex.setup.StartScreen
import com.jackra1n.dooplex.ui.LocalPageDip
import com.jackra1n.dooplex.ui.PageDip
import com.jackra1n.dooplex.ui.theme.DooplexTheme
import com.jackra1n.dooplex.watchlist.WatchlistState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(PlexBrowserState(busy = true))
    private lateinit var store: PlexAccountStore
    private lateinit var navigationStore: PlexNavigationStore
    private lateinit var accountClient: PlexAccountClient
    private var account: PlexAccount? = null
    private var accountAvatar by mutableStateOf<ImageBitmap?>(null)
    private var browser by mutableStateOf<Browser?>(null)
    private var splash by mutableStateOf(true)
    private val pageDip by lazy { PageDip(lifecycleScope) }
    private var playingDurationMs = 0L
    private var actionJob: Job? = null
    private var actionVersion = 0

    private val nativePlayer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        Analytics.event("player-result", "code" to result.resultCode)
        val position = try {
            PlayerHandoff.readResult(result.data, playingDurationMs)
        } catch (exception: IllegalStateException) {
            state = state.copy(error = exception.message)
            null
        }
        PlaybackService.finish(this, position)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Analytics.start(this)
        Analytics.onCommand = ::runCommand
        // A fresh screen never receives the result of a player an earlier one opened, so that
        // session ends here, with the last position read from the player.
        if (savedInstanceState == null && PlaybackMonitor.state.value.running) {
            Analytics.event("player-orphaned")
            PlaybackService.finish(this, null)
        }
        // The Compose surface covers the whole window, so the window's own fill is wasted GPU work.
        window.setBackgroundDrawable(null)
        store = PlexAccountStore(this)
        navigationStore = PlexNavigationStore(this)
        accountClient = PlexAccountClient(store.clientId)
        setContent {
            val playback by PlaybackMonitor.state.collectAsState()
            val destinations = rememberSaveableStateHolder()
            DooplexTheme {
                CompositionLocalProvider(LocalPageDip provides pageDip) {
                    val current = browser
                    if (state.stage == BrowserStage.TITLES && current != null) {
                        BrowserScreen(
                            browser = current,
                            destinations = destinations,
                            blocked = playback.running || state.busy,
                            error = state.error,
                            accountName = state.accountName,
                            accountAvatar = accountAvatar,
                            onPlay = ::play,
                            onSwitchServer = ::showServerPicker,
                            onSignOut = ::signOut,
                            onLeave = ::leaveApp,
                            onDismissError = ::clearError,
                        )
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
                            onBack = ::navigateBack,
                            onSignOut = ::signOut,
                            onRetry = ::retry,
                        )
                    }
                    StartScreenCover()
                }
            }
        }
        lifecycleScope.launch {
            PlaybackMonitor.state.collect { playback ->
                if (!playback.running && playback.phase != "Ready") browser?.refreshAfterPlayback()
            }
        }
        runAction {
            val saved = store.load()
            if (saved == null) {
                linkAccount()
            } else {
                account = saved
                refreshAccount(saved)
                showServers(restore = true)
            }
        }
    }

    /** Covers everything until Home is ready, so the first page never assembles itself on screen. */
    @Composable
    private fun StartScreenCover() {
        val current = browser
        val waiting = state.busy || state.stage == BrowserStage.TITLES && current != null && current.tab == "home" && !current.homeReady
        LaunchedEffect(splash, waiting, state.error) {
            if (splash && (!waiting || state.error != null)) splash = false
        }
        LaunchedEffect(splash) {
            if (splash) {
                delay(SPLASH_LIMIT_MS)
                splash = false
            }
        }
        val alpha by animateFloatAsState(if (splash) 1f else 0f, tween(400), label = "start screen")
        if (alpha > 0f) StartScreen(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
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

    private fun blocked() = PlaybackMonitor.state.value.running || state.busy

    private fun clearError() {
        state = state.copy(error = null)
    }

    /** Keeps the saved name and picture current; an account saved before pictures existed gets one. */
    private fun refreshAccount(saved: PlexAccount) {
        lifecycleScope.launch {
            val current = try {
                accountClient.account(saved.token).also { if (it != saved) store.save(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saved
            }
            if (account?.token != current.token) return@launch
            account = current
            if (state.accountName.isNotEmpty()) state = state.copy(accountName = current.displayName)
            showAvatar(current)
        }
    }

    private fun showAvatar(current: PlexAccount) {
        val url = current.avatarUrl ?: return
        lifecycleScope.launch {
            val picture = try {
                accountClient.avatar(url, AVATAR_PX).asImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            if (account?.token == current.token) accountAvatar = picture
        }
    }

    /** Commands from tools/bench.py, so every benchmark run starts from the same state. */
    private fun runCommand(command: String) {
        val current = browser ?: return
        if (blocked()) return
        val focus = current.focus
        val target = command.substringAfter(':')
        when (command) {
            "clear-memory", "clear-disk" -> {
                current.posters.clearMemory()
                if (command == "clear-disk") current.posters.clearDisk()
                clearWashes()
            }
            "home" -> {
                splash = true
                current.restart()
            }
            "open:movie", "open:show" -> current.reopen(target)
            "focus" -> focus.enterContent(current.tab)
            "tab:home" -> {
                focus.enterContent("home")
                current.showHome()
            }
            "tab:search", "tab:watchlist" -> {
                focus.enterContent(target)
                current.openPage(target)
            }
            "tab:movie", "tab:show" -> {
                focus.enterContent(target)
                current.selectCollection(target)
            }
            else -> if (command.startsWith("type:")) current.search?.type(target)
        }
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
                    account = linked
                    showAvatar(linked)
                    splash = true
                    state = state.copy(busy = true, pin = null, pinQr = null)
                    showServers(restore = true)
                    return@runAction
                }
            }
            state = state.copy(pin = null, pinQr = null, error = "The sign-in code expired. Choose Try again for a new one.")
        }
    }

    /** Restore IDs only after resolving the account's currently authorized server and libraries. */
    private suspend fun showServers(restore: Boolean = false) {
        val current = account ?: return
        state = PlexBrowserState(stage = BrowserStage.SERVERS, accountName = current.displayName, busy = true)
        val servers = accountClient.servers(current.token)
        state = state.copy(servers = servers)
        if (restore) {
            val saved = navigationStore.load()
            val server = saved.serverFrom(servers)
            if (server != null) connectServer(server)
            else if (saved.serverId != null)
                state = state.copy(error = "Your saved server is not available. Choose a server to continue.")
        }
    }

    private fun showServerPicker() {
        if (blocked()) return
        runAction { showServers() }
    }

    private fun selectServer(server: PlexServer) {
        if (blocked()) return
        if (server.id == browser?.server?.id) return resume()
        runAction { connectServer(server) }
    }

    private suspend fun connectServer(server: PlexServer) {
        val token = checkNotNull(account).token
        val connection = accountClient.connect(server)
        val libraries = PlexClient(connection).libraries()
        navigationStore.rememberServer(server.id)
        val selection = navigationStore.load()
        closeBrowser()
        browser = Browser(
            server = server,
            connection = connection,
            libraries = libraries,
            savedSelection = selection,
            navigationStore = navigationStore,
            watchlist = WatchlistState(accountClient, token, PlexClient(connection), server.name, lifecycleScope),
            posters = PosterRepository(this, connection),
            scope = lifecycleScope,
            dip = pageDip,
            blocked = ::blocked,
            onNavigate = ::clearError,
        )
        resume()
    }

    private fun resume() {
        state = state.copy(stage = BrowserStage.TITLES, busy = false, error = null)
    }

    /** Back on Home leaves for the launcher but keeps the app ready to resume, as Android 12+ does. */
    private fun leaveApp() {
        moveTaskToBack(true)
    }

    private fun navigateBack() {
        if (PlaybackMonitor.state.value.running) return
        when (state.stage) {
            BrowserStage.SIGN_IN -> leaveApp()
            BrowserStage.SERVERS -> if (browser != null) {
                cancelAction()
                resume()
            } else leaveApp()
            BrowserStage.TITLES -> browser?.back()
        }
    }

    private fun retry() {
        when (state.stage) {
            BrowserStage.SIGN_IN -> linkAccount()
            BrowserStage.SERVERS -> runAction { showServers() }
            BrowserStage.TITLES -> browser?.retry()
        }
    }

    private fun signOut() {
        if (PlaybackMonitor.state.value.running) return
        runAction {
            forgetAccount()
            state = PlexBrowserState(busy = true)
            store.clear()
            navigationStore.clear()
            linkAccount()
        }
    }

    private fun forgetAccount() {
        closeBrowser()
        account = null
        accountAvatar = null
    }

    private fun closeBrowser() {
        browser?.close()
        browser = null
    }

    private fun play(item: PlexBrowseItem, startFromBeginning: Boolean = false) {
        if (blocked()) return
        val current = browser ?: return
        runAction {
            val original = PlexClient(current.connection).loadMedia(item.ratingKey)
            val media = if (startFromBeginning) original.copy(resumePositionMs = 0) else original
            val launch = PlayerHandoff.prepare(this, media)
            current.played(item)
            playingDurationMs = media.durationMs
            PlaybackService.begin(this, current.connection, media, launch.liveStatus)
            try {
                nativePlayer.launch(launch.intent)
            } catch (_: ActivityNotFoundException) {
                PlaybackService.finish(this, null)
                throw IllegalStateException("No video player is installed. Install one, such as VLC or Just Player, and try again.")
            } catch (_: Exception) {
                PlaybackService.finish(this, null)
                throw IllegalStateException("Android could not start the video player.")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Analytics.event("lifecycle", "trim" to level)
        if (level == TRIM_MEMORY_UI_HIDDEN || level < TRIM_MEMORY_RUNNING_LOW) return
        browser?.posters?.clearMemory()
        clearWashes()
    }

    override fun onStop() {
        super.onStop()
        Analytics.event("lifecycle", "stop" to true)
    }

    override fun onDestroy() {
        Analytics.event("lifecycle", "destroy" to true, "finishing" to isFinishing, "changing" to isChangingConfigurations)
        closeBrowser()
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
                forgetAccount()
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
        const val AVATAR_PX = 128
    }
}

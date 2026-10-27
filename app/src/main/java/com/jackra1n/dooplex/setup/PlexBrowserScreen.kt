package com.jackra1n.dooplex.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jackra1n.dooplex.playback.PlaybackProofStatus
import com.jackra1n.dooplex.plex.PlexLibrary
import com.jackra1n.dooplex.plex.PlexServer
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.CornerColors
import com.jackra1n.dooplex.ui.CornerGround
import com.jackra1n.dooplex.ui.theme.CinemaColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlexBrowserScreen(
    state: PlexBrowserState,
    playback: PlaybackProofStatus,
    onLink: () -> Unit,
    onServer: (PlexServer) -> Unit,
    onLibrary: (PlexLibrary) -> Unit,
    onServers: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val firstItemKey = when (state.stage) {
        BrowserStage.SIGN_IN -> "link"
        BrowserStage.SERVERS -> state.servers.firstOrNull()?.id ?: "empty"
        BrowserStage.LIBRARIES -> state.libraries.firstOrNull()?.key ?: "empty"
        BrowserStage.TITLES -> "empty"
    }
    val destination = remember(state.stage, state.heading, firstItemKey) {
        listOf(state.stage.name, state.heading, firstItemKey)
    }
    var focusedDestination by remember { mutableStateOf<List<Any?>?>(null) }

    // Network polling and playback evidence must not move focus away from the user's selection.
    LaunchedEffect(destination, state.busy) {
        if (!state.busy && focusedDestination != destination) {
            if (state.stage != BrowserStage.SIGN_IN) listState.scrollToItem(0)
            withFrameNanos { }
            if (firstAction.requestFocus()) focusedDestination = destination
        }
    }

    BackHandler(enabled = state.canGoBack && !playback.running, onBack = onBack)

    CornerGround(CornerColors.Welcome, Modifier.fillMaxSize())
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DOOPLEX", color = CinemaColors.Accent, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            if (state.accountName.isNotBlank()) {
                Text(
                    state.accountName,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 18.sp,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (state.stage == BrowserStage.LIBRARIES) {
                CinemaButton(onClick = onServers, enabled = !state.busy && !playback.running) {
                    Text("Servers", fontSize = 18.sp)
                }
            }
            if (state.canGoBack) {
                CinemaButton(onClick = onBack, enabled = !playback.running) {
                    Text("Back", fontSize = 18.sp)
                }
            }
            if (state.stage != BrowserStage.SIGN_IN) {
                CinemaButton(onClick = onDisconnect, enabled = !playback.running) {
                    Text("Disconnect", fontSize = 18.sp)
                }
            }
        }
        if (state.stage != BrowserStage.SIGN_IN) Text(
            state.heading,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (state.stage == BrowserStage.SIGN_IN) {
            SignInPanel(
                state = state,
                firstAction = firstAction,
                onLink = onLink,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.error,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (firstItemKey != "empty") {
                            CinemaButton(onClick = onRetry, enabled = !state.busy && !playback.running) {
                                Text("Retry", fontSize = 18.sp)
                            }
                        }
                    }
                }
                if (state.busy) {
                    Text(
                        "Loading Plex…",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).widthIn(max = 820.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.stage) {
                        BrowserStage.SERVERS -> itemsIndexed(state.servers, key = { _, server -> "server:${server.id}" }) { index, server ->
                            BrowserChoice(
                                title = server.name,
                                subtitle = "Open server",
                                action = "Open",
                                enabled = !state.busy && !playback.running,
                                modifier = if (index == 0) Modifier.focusRequester(firstAction) else Modifier,
                                onClick = { onServer(server) },
                            )
                        }
                        BrowserStage.LIBRARIES -> itemsIndexed(state.libraries, key = { _, library -> "library:${library.key}" }) { index, library ->
                            BrowserChoice(
                                title = library.title,
                                subtitle = if (library.type == "show") "TV shows" else "Movies",
                                action = "Browse",
                                enabled = !state.busy && !playback.running,
                                modifier = if (index == 0) Modifier.focusRequester(firstAction) else Modifier,
                                onClick = { onLibrary(library) },
                            )
                        }
                        BrowserStage.TITLES -> Unit
                        BrowserStage.SIGN_IN -> Unit
                    }
                    if (firstItemKey == "empty" && !state.busy) {
                        item(key = "empty") {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    when {
                                        state.error != null -> "Plex could not load this view. Try again or go back."
                                        state.stage == BrowserStage.SERVERS -> "No Plex servers found. Check that your server is online and shared with this account."
                                        state.stage == BrowserStage.LIBRARIES -> "No movie or TV libraries found on this server."
                                        else -> "Choose a source to continue."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 20.sp,
                                )
                                CinemaButton(
                                    onClick = onRetry,
                                    enabled = !playback.running,
                                    modifier = Modifier.focusRequester(firstAction),
                                ) { Text(if (state.error != null) "Retry" else "Refresh", fontSize = 18.sp) }
                            }
                        }
                    }
                }
            }
        }
        if (playback.title.isNotBlank() || playback.running || playback.phase != "Ready") {
            PlaybackEvidence(playback)
        }
    }
}

/** The QR is plex.tv's own; the typed plex.tv/link code stays as a small fallback beside it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SignInPanel(
    state: PlexBrowserState,
    firstAction: FocusRequester,
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pin = state.pin
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Sign in with your phone", color = CinemaColors.Foreground, fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold)
            Text("Scan the code with your phone’s camera and approve Dooplex in Plex. This screen continues on its own.",
                color = CinemaColors.Muted, fontSize = 18.sp, lineHeight = 27.sp)
            Text(
                when {
                    state.error != null -> state.error
                    pin == null -> "Getting a sign-in code…"
                    else -> "Waiting for you to sign in…"
                },
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.error != null) CinemaColors.Error else CinemaColors.Foreground.copy(alpha = 0.86f),
                fontSize = 16.sp,
            )
            if (pin != null && state.pinQr != null) Text(
                buildAnnotatedString {
                    append("No camera? Open ")
                    withStyle(SpanStyle(color = CinemaColors.Foreground, fontWeight = FontWeight.SemiBold)) { append("plex.tv/link") }
                    append(" and enter ")
                    withStyle(SpanStyle(color = CinemaColors.Foreground, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)) {
                        append(pin.code)
                    }
                },
                color = CinemaColors.Muted, fontSize = 15.sp,
            )
            CinemaButton(
                onClick = onLink,
                enabled = !state.busy || pin != null,
                modifier = Modifier.padding(top = 10.dp).focusRequester(firstAction),
            ) {
                Text(if (state.error != null) "Try again" else "New code", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        Box(
            Modifier.size(280.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // plex.tv draws white modules on transparency for dark screens; tinting them dark on
                // this white card gives the classic QR every phone camera reads.
                state.pinQr != null -> Image(
                    bitmap = state.pinQr.asImageBitmap(),
                    contentDescription = "QR code to sign in to Plex",
                    colorFilter = ColorFilter.tint(CinemaColors.OnAccent),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
                pin != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("plex.tv/link", color = CinemaColors.OnAccent, fontSize = 18.sp)
                    Text(pin.code, color = CinemaColors.OnAccent, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                }
                else -> Text("…", color = CinemaColors.OnAccent, fontSize = 32.sp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowserChoice(
    title: String,
    subtitle: String,
    action: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CinemaButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(24.dp))
        Text(action, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackEvidence(playback: PlaybackProofStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Native playback · ${playback.phase}",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (playback.title.isNotBlank()) {
            Text(
                "${playback.title} · ${playbackTime(playback.positionMs)} / ${playbackTime(playback.durationMs)}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            playback.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** Covers start-up until the first real screen is ready, so the app never shows a page filling in. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StartScreen(modifier: Modifier) {
    val sweep = rememberInfiniteTransition(label = "start").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "start sweep",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        CornerGround(CornerColors.Welcome, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("DOOPLEX", color = CinemaColors.Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 7.sp)
            Box(
                Modifier.size(width = 120.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .drawWithContent {
                        val width = size.width * 0.35f
                        drawRect(Color.White.copy(alpha = 0.7f), Offset((size.width + width) * sweep.value - width, 0f), Size(width, size.height))
                    },
            )
        }
    }
}

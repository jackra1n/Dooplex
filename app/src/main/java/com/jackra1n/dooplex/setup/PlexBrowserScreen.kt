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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.jackra1n.dooplex.playback.PlaybackProofStatus
import com.jackra1n.dooplex.plex.PlexServer
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.CornerColors
import com.jackra1n.dooplex.ui.CornerGround
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

@Composable
fun PlexBrowserScreen(
    state: PlexBrowserState,
    playback: PlaybackProofStatus,
    onLink: () -> Unit,
    onServer: (PlexServer) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    val destination = listOf(state.stage, state.servers.map { it.id }, state.error != null)
    var focusedDestination by remember { mutableStateOf<List<Any?>?>(null) }

    // Network polling must not move focus away from the user's selection.
    LaunchedEffect(destination, state.busy) {
        if (!state.busy && focusedDestination != destination) {
            withFrameNanos { }
            if (firstAction.requestFocus()) focusedDestination = destination
        }
    }

    BackHandler(enabled = !playback.running, onBack = onBack)

    CornerGround(CornerColors.Welcome, Modifier.fillMaxSize())
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 56.dp, vertical = 40.dp),
    ) {
        Text("DOOPLEX", color = CinemaColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp, modifier = Modifier.align(Alignment.TopStart))
        when (state.stage) {
            BrowserStage.SIGN_IN -> SignInPanel(state, firstAction, onLink, Modifier.fillMaxSize())
            BrowserStage.SERVERS -> ServerPicker(
                state = state,
                firstAction = firstAction,
                enabled = !state.busy && !playback.running,
                onServer = onServer,
                onSignOut = onSignOut,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            BrowserStage.TITLES -> Unit
        }
    }
}

@Composable
private fun ServerPicker(
    state: PlexBrowserState,
    firstAction: FocusRequester,
    enabled: Boolean,
    onServer: (PlexServer) -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.busy && state.servers.isEmpty()) "Finding your servers…" else "Choose a server",
            color = CinemaColors.Foreground, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (state.busy && state.servers.isNotEmpty()) "Connecting…" else "Signed in as ${state.accountName}",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = CinemaColors.Muted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        state.error?.let {
            Text(it, color = CinemaColors.Error, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Column(
            Modifier.padding(top = 12.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.servers.forEachIndexed { index, server ->
                CinemaButton(
                    onClick = { onServer(server) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(server.name, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(reach(server), fontSize = 15.sp, maxLines = 1)
                    }
                    Text("›", fontSize = 26.sp)
                }
            }
            if (state.servers.isEmpty() && !state.busy) {
                Text("No Plex servers found. Check that your server is running and shared with this account.",
                    color = CinemaColors.Muted, fontSize = 18.sp, lineHeight = 26.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.servers.isEmpty() || state.error != null) CinemaButton(
                onClick = onRetry,
                enabled = !state.busy,
                modifier = if (state.servers.isEmpty()) Modifier.focusRequester(firstAction) else Modifier,
            ) { Text("Try again", fontSize = 16.sp) }
            CinemaButton(onClick = onSignOut, enabled = !state.busy) { Text("Sign out", fontSize = 16.sp) }
        }
    }
}

private fun reach(server: PlexServer): String = when {
    server.connections.any { it.local } -> "On this network"
    server.connections.any { !it.relay } -> "Over the internet"
    else -> "Through Plex Relay"
}

/** The QR is plex.tv's own; the typed plex.tv/link code stays as a small fallback beside it. */
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

/** Covers start-up until the first real screen is ready, so the app never shows a page filling in. */
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

package com.jackra1n.dooplex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlexBrowserScreen(
    state: PlexBrowserState,
    playback: PlaybackProofStatus,
    onLink: () -> Unit,
    onServer: (PlexServer) -> Unit,
    onLibrary: (PlexLibrary) -> Unit,
    onItem: (PlexBrowseItem) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
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
        BrowserStage.TITLES -> state.page?.items?.firstOrNull()?.ratingKey ?: "empty"
    }
    val destination = remember(state.stage, state.heading, state.page?.start, firstItemKey) {
        listOf(state.stage.name, state.heading, state.page?.start, firstItemKey)
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

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Dooplex",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
            if (state.canGoBack) {
                BrowserButton(onClick = onBack, enabled = !playback.running) {
                    Text("Back", fontSize = 18.sp)
                }
            }
            if (state.stage != BrowserStage.SIGN_IN) {
                BrowserButton(onClick = onDisconnect, enabled = !playback.running) {
                    Text("Disconnect", fontSize = 18.sp)
                }
            }
        }
        Text(
            state.heading,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
                            BrowserButton(onClick = onRetry, enabled = !state.busy && !playback.running) {
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                        BrowserStage.TITLES -> itemsIndexed(state.page?.items.orEmpty(), key = { _, item -> "title:${item.ratingKey}" }) { index, item ->
                            BrowserChoice(
                                title = item.title,
                                subtitle = item.subtitle,
                                action = if (item.childrenPath != null) "Open" else "Play",
                                enabled = !state.busy && !playback.running,
                                modifier = if (index == 0) Modifier.focusRequester(firstAction) else Modifier,
                                onClick = { onItem(item) },
                            )
                        }
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
                                        else -> "No titles found in this view."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 20.sp,
                                )
                                BrowserButton(
                                    onClick = onRetry,
                                    enabled = !playback.running,
                                    modifier = Modifier.focusRequester(firstAction),
                                ) { Text(if (state.error != null) "Retry" else "Refresh", fontSize = 18.sp) }
                            }
                        }
                    }
                }
                if (state.stage == BrowserStage.TITLES && state.page != null) {
                    val page = state.page
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrowserButton(onClick = onPreviousPage, enabled = !state.busy && !playback.running && page.start > 0) {
                            Text("Previous", fontSize = 18.sp)
                        }
                        Text(
                            if (page.items.isEmpty()) "0 titles" else "${page.start + 1}–${page.start + page.items.size} of ${page.total}",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 18.sp,
                        )
                        BrowserButton(onClick = onNextPage, enabled = !state.busy && !playback.running && page.hasNext) {
                            Text("Next", fontSize = 18.sp)
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SignInPanel(
    state: PlexBrowserState,
    firstAction: FocusRequester,
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "On your phone or laptop, open plex.tv/link and sign in to Plex.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
        )
        if (state.pin != null) {
            Text("Enter this TV code", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            Text(
                state.pin.code,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
            )
            Text(
                if (state.error != null) "Request a new code below to try linking again."
                else "Waiting for approval… Keep this screen open while you link your account.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
        } else {
            Text(
                if (state.busy) "Getting your link code…" else "Choose Get link code, then enter the code on that page. No remote typing needed.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 20.sp,
            )
        }
        if (state.error != null) {
            Text(
                state.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                fontSize = 18.sp,
            )
        }
        BrowserButton(
            onClick = onLink,
            enabled = !state.busy || state.pin != null,
            modifier = Modifier.focusRequester(firstAction),
        ) {
            Text(if (state.pin == null) "Get link code" else "Get new code", fontSize = 20.sp)
        }
        Spacer(Modifier.height(4.dp))
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
    BrowserButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
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
private fun BrowserButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        content = content,
    )
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

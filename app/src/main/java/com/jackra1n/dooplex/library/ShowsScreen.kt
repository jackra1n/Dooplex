package com.jackra1n.dooplex.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.artwork.ArtworkImage
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.artwork.rememberArtworkReveal
import com.jackra1n.dooplex.artwork.rememberMovieArtworkPainter
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.CollectionPosterCard
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.bleed
import com.jackra1n.dooplex.ui.rememberGridRowScroller
import com.jackra1n.dooplex.ui.theme.CinemaColors
import com.jackra1n.dooplex.ui.pageFade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One full-span header shares the lazy viewport with index-keyed cards, including unloaded slots.
 * Page fills never replace focus nodes. Vertical movement keeps the column and reveals the complete
 * scaled episode card inside the same TV-safe inset as the sides, including the final metadata line.
 * Header focus instead reveals index zero and all top spacing. Scroll and focus survive returns.
 * Parent navigation intent suppresses poster restoration until Down explicitly enters this header;
 * tab confirmation reveals all chrome while retaining the content viewport for deliberate re-entry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShowsScreen(
    state: ShowBrowseState,
    posters: PosterRepository,
    enabled: Boolean,
    error: String?,
    onBack: () -> Unit,
    onItem: (PlexBrowseItem) -> Unit,
    navigation: @Composable () -> Unit,
    focus: CollectionFocusState,
) {
    val grid = rememberSaveable(state.path, saver = LazyGridState.Saver) { LazyGridState() }
    val scroller = rememberGridRowScroller(grid)
    var focusedIndex by rememberSaveable(state.path) { mutableIntStateOf(0) }
    var headerSelected by rememberSaveable(state.path) { mutableStateOf(false) }
    val requesters = remember(state) { mutableStateMapOf<Int, FocusRequester>() }
    val route = "show:${state.path}"
    var navigationViewportIndex by rememberSaveable(state.path) { mutableIntStateOf(-1) }
    var navigationViewportOffset by rememberSaveable(state.path) { mutableIntStateOf(0) }
    var headerMounted by remember(state) { mutableStateOf(false) }
    var needsFocus by remember(state) { mutableStateOf(true) }
    var pendingIndex by remember(state) { mutableStateOf<Int?>(null) }
    var revealHeader by remember(state) { mutableStateOf(false) }
    var directional by remember(state) { mutableStateOf(false) }
    var focusEpoch by remember(state) { mutableIntStateOf(0) }
    var artworkRange by remember(state) { mutableStateOf(IntRange.EMPTY) }
    val bringSpec = remember(state) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (directional || revealHeader || focus.navigationType != null) 0f
                else super.calculateScrollDistance(offset, size, containerSize)
        }
    }

    fun moveFocus(index: Int) {
        val from = pendingIndex ?: focusedIndex
        pendingIndex = index
        focusedIndex = index
        headerSelected = false
        revealHeader = false
        directional = true
        needsFocus = true
        focusEpoch++
        if (index / scroller.columns != from / scroller.columns) scroller.showCard(index)
    }

    fun focusHeader() {
        pendingIndex = null
        headerSelected = true
        revealHeader = true
        directional = true
        needsFocus = true
        focusEpoch++
    }

    fun enterContent() {
        pendingIndex = focusedIndex
        headerSelected = false
        revealHeader = false
        directional = false
        needsFocus = true
        focusEpoch++
    }

    LaunchedEffect(state, focus.contentEpoch) {
        if (focus.contentType != "show") return@LaunchedEffect
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.any { it.index == 1 } }.first { it }
        focusedIndex = 0
        navigationViewportIndex = -1
        enterContent()
        focus.completeContent("show")
    }
    LaunchedEffect(state, focus.navigationType, focus.navigationEpoch) {
        if (focus.navigationType == null) return@LaunchedEffect
        if (navigationViewportIndex < 0) {
            navigationViewportIndex = grid.firstVisibleItemIndex
            navigationViewportOffset = grid.firstVisibleItemScrollOffset
        }
        needsFocus = false
        pendingIndex = null
        revealHeader = false
        directional = false
        focusEpoch++
        grid.scrollToItem(0)
        snapshotFlow { headerMounted && grid.layoutInfo.visibleItemsInfo.any { it.index == 0 } }.first { it }
        withFrameNanos { }
        focus.navigationType?.let { focus.tab(it).requestFocus() }
    }
    LaunchedEffect(route, focus.headerEpoch, enabled) {
        if (focus.headerRoute != route || !enabled) return@LaunchedEffect
        needsFocus = true
        headerSelected = true
        revealHeader = true
        directional = false
        focusEpoch++
    }

    BackHandler(enabled = enabled, onBack = onBack)
    LaunchedEffect(enabled) {
        if (enabled && focus.navigationType == null) {
            needsFocus = true
            focusEpoch++
        }
    }
    // Shrink only relocates an invalid selection; ordinary page fills never request focus again.
    LaunchedEffect(state.total, state.loading) {
        if (state.total > 0 && focusedIndex >= state.total) {
            if (headerSelected) focusedIndex = state.total - 1 else moveFocus(state.total - 1)
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(CinemaColors.Panel, CinemaColors.Canvas, CinemaColors.Canvas)),
        ).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val compact = maxWidth < 700.dp
        val edge = if (compact) 24.dp else 40.dp
        val episodes = state.level == ShowBrowseLevel.EPISODES
        val columns = if (episodes) {
            when { maxWidth >= 1100.dp -> 4; maxWidth >= 780.dp -> 3; else -> 2 }
        } else {
            when { maxWidth >= 1400.dp -> 7; maxWidth >= 1000.dp -> 6; maxWidth >= 780.dp -> 5; maxWidth >= 580.dp -> 4; else -> 3 }
        }
        val density = LocalDensity.current
        // The layout reserves the scale overhang too, so the visible card retains the full safe inset.
        val episodeOverhang = if (episodes) maxWidth / columns * 0.0175f else 0.dp
        val horizontalEdge = edge + episodeOverhang
        val bottomEdge = if (episodes) edge + maxHeight * 0.0175f else 36.dp
        val safeInsetPx = with(density) { if (episodes) edge.toPx() else 0f }
        val artworkWidth = artworkBucket(with(density) { ((maxWidth - horizontalEdge * 2 - 18.dp * (columns - 1)) / columns).toPx() })
        val count = if (state.total > 0) state.total else if (state.loading) columns * 2 else 0
        val rowSpacing = with(density) { (if (compact) 18.dp else 24.dp).toPx() }
        SideEffect {
            scroller.columns = columns
            scroller.spacing = rowSpacing
        }
        val noun = when (state.level) {
            ShowBrowseLevel.SEASONS -> "season"
            ShowBrowseLevel.EPISODES -> "episode"
        }

        LaunchedEffect(state, grid, columns, enabled) {
            if (!enabled) return@LaunchedEffect
            snapshotFlow {
                val cards = grid.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
                if (cards.isEmpty()) null else (cards.first().index - 1) to (cards.last().index - 1)
            }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
                artworkRange = first..last
                state.requestRange(first, last)
            }
        }

        LaunchedEffect(focusEpoch, enabled, state.total, state.loading, columns, focus.navigationType, focus.headerRoute, focus.contentType) {
            if (!enabled || focus.navigationType != null || focus.contentType == "show" || focus.headerRoute?.let { it != route } == true ||
                !needsFocus && !revealHeader) return@LaunchedEffect
            val epoch = focusEpoch
            if (revealHeader || headerSelected || count == 0) {
                coroutineScope {
                    if (directional) scroller.showHeader() else grid.scrollToItem(0)
                    snapshotFlow { headerMounted && grid.layoutInfo.visibleItemsInfo.any { it.index == 0 } }.first { it }
                    withFrameNanos { }
                    if (needsFocus && epoch == focusEpoch) {
                        // The header holds no controls: coming down from the bar goes on to the first card,
                        // and going up from the cards reaches the bar.
                        (requesters[0].takeIf { focus.headerRoute == route } ?: focus.tab("show")).requestFocus()
                        focus.completeHeader(route)
                    }
                }
            } else {
                if (!directional && navigationViewportIndex >= 0) {
                    grid.scrollToItem(navigationViewportIndex, navigationViewportOffset)
                    navigationViewportIndex = -1
                }
                val target = (pendingIndex ?: focusedIndex).coerceIn(0, count - 1)
                val lazyIndex = target + 1
                coroutineScope {
                    val layout = grid.layoutInfo
                    val visible = layout.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
                    // Directional moves are already gliding on the row scroller.
                    if (episodes && !directional) {
                        val height = visible?.size?.height
                            ?: layout.visibleItemsInfo.firstOrNull { it.index > 0 }?.size?.height
                            ?: (layout.viewportEndOffset - layout.viewportStartOffset)
                        val overhang = if (episodes) height * 0.0175f else 0f
                        // Keep both edges safe whenever the complete card fits. On exceptionally short
                        // viewports, center an oversized card rather than alternating edge corrections.
                        val margin = (safeInsetPx + overhang).coerceAtMost(
                            ((layout.viewportEndOffset - layout.viewportStartOffset - height) / 2f).coerceAtLeast(0f),
                        )
                        val safeTop = layout.viewportStartOffset + margin
                        val safeBottom = layout.viewportEndOffset - margin
                        val below = visible?.let { it.offset.y + height > safeBottom }
                            ?: (lazyIndex > grid.firstVisibleItemIndex)
                        val above = visible?.let { it.offset.y < safeTop } ?: !below
                        if (above || below) {
                            val top = when {
                                height > layout.viewportEndOffset - layout.viewportStartOffset ->
                                    (layout.viewportStartOffset + layout.viewportEndOffset - height) / 2f
                                below -> safeBottom - height
                                else -> safeTop
                            }
                            // Scroll the row, never the column: focus changes cannot pan horizontally.
                            launch { grid.animateScrollToItem(1 + target / columns * columns, -top.toInt()) }
                        }
                    } else if (!directional && visible == null) grid.scrollToItem(lazyIndex)
                    val requester = snapshotFlow {
                        requesters[target]?.takeIf { grid.layoutInfo.visibleItemsInfo.any { it.index == lazyIndex } }
                    }.filterNotNull().first()
                    withFrameNanos { }
                    if (needsFocus && epoch == focusEpoch) {
                        focusedIndex = target
                        requester.requestFocus()
                    }
                }
            }
            if (epoch == focusEpoch) {
                pendingIndex = null
                needsFocus = false
                revealHeader = false
                directional = false
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides bringSpec) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns), state = grid,
                contentPadding = PaddingValues(start = horizontalEdge, end = horizontalEdge, bottom = bottomEdge),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp),
                modifier = Modifier.fillMaxSize().focusGroup(),
            ) {
                item(key = "show-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    DisposableEffect(Unit) {
                        headerMounted = true
                        onDispose { headerMounted = false }
                    }
                    CollectionHeaderChrome(
                        title = state.title,
                        subtitle = if (episodes) "Episodes" else "Seasons",
                        count = if (state.total == 0 && state.loading) "Loading ${noun}s…" else
                            "${state.total} $noun${if (state.total == 1) "" else "s"}${if (state.loading) " · Loading…" else ""}",
                        hint = null,
                        navigation = { Box(Modifier.bleed(start = horizontalEdge, end = horizontalEdge)) { navigation() } },
                        compact = compact,
                        actions = {
                            if (state.error != null) CinemaButton(onClick = state::retry, enabled = enabled) { ShowControlText("Retry") }
                        },
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (it.hasFocus && !needsFocus && focus.navigationType == null) {
                                headerSelected = true
                                if (enabled && grid.canScrollBackward && !revealHeader) {
                                    revealHeader = true
                                    directional = true
                                    focusEpoch++
                                }
                            } else if (!it.hasFocus && revealHeader && !needsFocus) {
                                revealHeader = false
                                directional = false
                                focusEpoch++
                            }
                        }.onPreviewKeyEvent { event ->
                            if (enabled && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown &&
                                !needsFocus && !revealHeader && grid.canScrollBackward) {
                                revealHeader = true
                                directional = true
                                focusEpoch++
                            }
                            false
                        }.focusGroup().onPreviewKeyEvent { event ->
                            if (enabled && focus.navigationType == null && event.key == Key.DirectionDown && count > 0) {
                                if (event.type == KeyEventType.KeyDown) enterContent()
                                true
                            } else false
                        },
                    ) {
                        val message = error ?: state.error
                        if (message != null) Text(message, color = CinemaColors.Error, fontSize = 14.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (count == 0) {
                    item(key = "show-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                        Column(Modifier.fillMaxWidth().height(if (compact) 140.dp else 250.dp).pageFade(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (state.error != null) "Couldn't load this collection" else "No ${noun}s here yet",
                                color = CinemaColors.Foreground, fontSize = if (compact) 21.sp else 28.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(12.dp))
                            Text(if (state.error != null) "Choose Retry above to try again." else "Titles added to this Plex collection will appear here.",
                                color = CinemaColors.Muted, fontSize = if (compact) 14.sp else 17.sp)
                        }
                    }
                } else {
                    items(count = count, key = { it }, contentType = { if (episodes) "episode" else "portrait" }) { index ->
                        val item = state.itemAt(index)
                        val requester = remember(state, index) { FocusRequester() }
                        val inArtworkRange by remember(state, index) { derivedStateOf { index in artworkRange } }
                        DisposableEffect(requester) {
                            requesters[index] = requester
                            onDispose { requesters.remove(index) }
                        }
                        val cardModifier = Modifier.pageFade().focusRequester(requester).onPreviewKeyEvent { event ->
                            if (!enabled) false
                            else if (revealHeader && event.key == Key.DirectionUp) true
                            else {
                                val logical = pendingIndex ?: index
                                val column = logical % columns
                                val target = when (event.key) {
                                    Key.DirectionDown -> if ((logical / columns + 1) * columns < count) minOf(logical + columns, count - 1) else logical
                                    Key.DirectionUp -> if (logical >= columns) logical - columns else -1
                                    Key.DirectionLeft -> if (column > 0) logical - 1 else logical
                                    Key.DirectionRight -> if (column < columns - 1 && logical < count - 1) logical + 1 else logical
                                    else -> null
                                }
                                if (target == null) false else {
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (target < 0) focusHeader() else if (target != logical) moveFocus(target)
                                    }
                                    true
                                }
                            }
                        }
                        val onFocus = {
                            if (!needsFocus && pendingIndex == null) focusedIndex = index
                            headerSelected = false
                        }
                        val onClick = {
                            focusedIndex = index
                            // Pin the activated card before disposal can relocate focus to its neighbor.
                            if (item?.childrenPath != null) needsFocus = true
                            if (item != null) onItem(item) else if (state.error != null) state.retry() else state.requestRange(index, index)
                        }
                        if (episodes) EpisodeCard(item, posters, artworkWidth, enabled && inArtworkRange,
                            enabled, cardModifier, onFocus, onClick, "Episode ${index + 1}")
                        else CollectionPosterCard(item, posters, artworkWidth, enabled && inArtworkRange,
                            enabled, cardModifier, onFocus, onClick, "${noun.replaceFirstChar { it.uppercase() }} ${index + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowControlText(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable
private fun EpisodeCard(
    item: PlexBrowseItem?,
    posters: PosterRepository,
    artworkWidth: Int,
    loadArtwork: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    placeholderLabel: String,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val scale = animateFloatAsState(if (focused) 1.035f else 1f, tween(160), label = "episode focus")
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "episode highlight")
    val painter = rememberMovieArtworkPainter(item?.thumbPath, posters, artworkWidth, artworkWidth * 9 / 16, loadArtwork)
    val reveal = rememberArtworkReveal(item?.thumbPath, artworkWidth, painter != null)
    val status = remember(item) {
        if (item == null) " " else buildList {
            if (item.durationMs > 0) add("${(item.durationMs / 60000).coerceAtLeast(1)} min")
            if (item.watched) add("Watched")
            else if (item.viewOffsetMs > 0) add("Resume at ${episodePosition(item.viewOffsetMs)}")
        }.joinToString(" · ").ifBlank { " " }
    }
    Column(
        modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = { if (enabled) onClick() })
            .semantics { contentDescription = if (item == null) "$placeholderLabel, loading. Select to retry loading."
                else listOf(item.title, item.subtitle, status).filter { it.isNotBlank() }.joinToString(", ") }
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(CinemaColors.Panel)
            .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.07f), shape)
            .drawWithCache {
                val shine = Brush.linearGradient(listOf(CinemaColors.Foreground.copy(alpha = 0.22f), Color.Transparent,
                    CinemaColors.Foreground.copy(alpha = 0.06f)), Offset.Zero, Offset(size.width, size.height))
                val stroke = Stroke(1.dp.toPx())
                val corner = CornerRadius(9.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    if (light.value > 0f) {
                        drawRect(shine, alpha = light.value)
                        drawRoundRect(CinemaColors.Foreground, cornerRadius = corner, alpha = light.value * 0.65f, style = stroke)
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (painter != null) ArtworkImage(painter, reveal, Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Text((item?.title ?: placeholderLabel).take(1).uppercase(), color = CinemaColors.Accent.copy(alpha = 0.5f), fontSize = 32.sp)
                if (item != null && item.thumbPath == null) Text("NO ARTWORK", color = CinemaColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            if (item?.watched == true) Text("WATCHED", color = CinemaColors.Foreground, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(5.dp))
                    .background(CinemaColors.Canvas.copy(alpha = 0.88f)).padding(horizontal = 7.dp, vertical = 5.dp))
            if (item != null && item.viewOffsetMs > 0 && item.durationMs > 0 && !item.watched) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(CinemaColors.Canvas.copy(alpha = 0.8f))) {
                    Box(Modifier.fillMaxWidth((item.viewOffsetMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().background(CinemaColors.Accent))
                }
            }
        }
        Text(item?.title ?: "Loading…", color = CinemaColors.Foreground.copy(alpha = if (focused) 1f else 0.85f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item?.subtitle?.ifBlank { " " } ?: " ", color = CinemaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = if (item?.viewOffsetMs != null && item.viewOffsetMs > 0 && !item.watched) CinemaColors.Accent else CinemaColors.Muted,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun episodePosition(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    return "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}

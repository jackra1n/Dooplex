package com.jackra1n.dooplex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private const val COLLECTION_GRID_HEADER_ITEMS = 1

private suspend fun LazyGridState.scrollToCollectionLetter(index: Int) {
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > index }
    scroll {
        val scope = LazyLayoutScrollScope(this@scrollToCollectionLetter, this)
        val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        val cardHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index >= COLLECTION_GRID_HEADER_ITEMS }
            ?.size?.height?.toFloat() ?: viewport / 2
        val travel = minOf(viewport * 0.65f, cardHeight * 0.9f).coerceAtLeast(1f)
        var distance = scope.calculateDistanceTo(index).toFloat()
        // Long library jumps animate only the final row, never compose hundreds of intermediate titles.
        if (abs(distance) > travel) {
            val direction = sign(distance)
            // Measure bottom clamping before staging the approach to a short final bucket.
            scope.snapToItem(index)
            val settledOffset = scope.calculateDistanceTo(index)
            scope.snapToItem(index, -(settledOffset + direction * travel).roundToInt())
            distance = scope.calculateDistanceTo(index) - settledOffset.toFloat()
        }
        if (abs(distance) > 0.5f) {
            var consumed = 0f
            animate(0f, distance, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ ->
                consumed += scope.scrollBy(value - consumed)
            }
        }
        scope.snapToItem(index)
    }
}

/**
 * Header and posters share one lazy viewport; letter focus previews a jump without leaving the rail.
 * Movie details, show children, playback and tab returns retain the viewport and selected card.
 * Vertical DPAD targets retain their column and take focus when visible, without waiting for scroll completion.
 * Directional reveals own scrolling until completion so early focus cannot replace their animation.
 * Header focus reveals all chrome and top spacing, not just the active control's minimum bounds.
 * Navigation owns focus across route/busy transitions; only Down hands it to the compact header.
 * Its prior content viewport is retained separately while the complete header is revealed.
 * Visible artwork loads while scrolling; idle lookahead warms the rows ahead without competing with it.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CollectionScreen(
    state: CollectionLibraryState,
    posters: PosterRepository,
    navigation: @Composable () -> Unit,
    focus: CollectionFocusState,
    libraries: List<PlexLibrary>,
    onLibrary: (PlexLibrary) -> Unit,
    playbackRunning: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
    onOpenShow: (PlexBrowseItem) -> Unit,
) {
    val grid = rememberLazyGridState()
    val scroller = rememberGridRowScroller(grid)
    val requesters = remember(state, state.generation) { mutableStateMapOf<Int, FocusRequester>() }
    val sortFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val libraryFocus = remember { FocusRequester() }
    val route = "library:${state.library.key}"
    var navigationViewportIndex by rememberSaveable(state.library.key) { mutableIntStateOf(-1) }
    var navigationViewportOffset by rememberSaveable(state.library.key) { mutableIntStateOf(0) }
    var headerMounted by remember(state) { mutableStateOf(false) }
    var focusedIndex by rememberSaveable(state.library.key) { mutableIntStateOf(0) }
    // Dialog/window focus handoffs must not overwrite the poster chosen for restoration.
    var detailsIndex by remember(state) { mutableStateOf<Int?>(null) }
    var pendingFocusIndex by remember(state) { mutableStateOf<Int?>(null) }
    var directionalFocus by remember(state) { mutableStateOf(false) }
    val gridBringIntoView = remember(state) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                // A directional reveal owns the animation; focus must not start a competing scroll.
                if (directionalFocus || focus.navigationType != null) 0f
                else super.calculateScrollDistance(offset, size, containerSize)
        }
    }
    var sortOpen by remember { mutableStateOf(false) }
    var restoreToHeader by remember { mutableStateOf(false) }
    var restoreEpoch by remember { mutableIntStateOf(0) }
    var needsFocus by remember { mutableStateOf(true) }
    var wasPlaying by remember { mutableStateOf(playbackRunning) }
    var lastGeneration by remember(state) { mutableIntStateOf(state.generation) }
    var artworkRange by remember(state.generation) { mutableStateOf(IntRange.EMPTY) }
    var lookahead by remember(state.generation) { mutableStateOf<IntProgression>(IntRange.EMPTY) }
    var railHasFocus by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var awaitingJump by remember { mutableStateOf(false) }
    var returnAfterJump by remember { mutableStateOf(false) }
    var railRestorePending by remember { mutableStateOf(false) }
    var lastRailKey by rememberSaveable(state.library.key) { mutableStateOf<String?>(null) }
    val railStatusFocus = remember { FocusRequester() }
    val alphabet = remember(state.alphabet) { state.alphabet.filter { it.count > 0 } }
    val railRequesters = remember(alphabet) {
        alphabet.associate { it.key to FocusRequester() }
    }
    val selected = state.selectedMovie
    val modalOpen = sortOpen || selected != null

    fun restoreGrid() {
        restoreToHeader = false
        directionalFocus = false
        awaitingJump = false
        returnAfterJump = false
        railRestorePending = false
        pendingFocusIndex = focusedIndex
        needsFocus = true
        restoreEpoch++
    }

    fun revealHeader() {
        if (restoreToHeader || needsFocus || !grid.canScrollBackward) return
        restoreToHeader = true
        directionalFocus = true
        restoreEpoch++
    }

    fun leaveAlphabet() {
        if (awaitingJump) returnAfterJump = true else restoreGrid()
    }

    fun movePosterFocus(index: Int) {
        val from = pendingFocusIndex ?: focusedIndex
        focusedIndex = index
        pendingFocusIndex = index
        directionalFocus = true
        needsFocus = true
        restoreToHeader = false
        restoreEpoch++
        if (index / scroller.columns != from / scroller.columns) scroller.showCard(index)
    }

    fun closeDetails() {
        focusedIndex = detailsIndex ?: focusedIndex
        detailsIndex = null
        state.closeDetails()
        restoreGrid()
    }

    LaunchedEffect(state, focus.navigationType, focus.navigationEpoch) {
        if (focus.navigationType == null) return@LaunchedEffect
        if (navigationViewportIndex < 0) {
            navigationViewportIndex = grid.firstVisibleItemIndex
            navigationViewportOffset = grid.firstVisibleItemScrollOffset
        }
        needsFocus = false
        pendingFocusIndex = null
        restoreToHeader = false
        directionalFocus = false
        railRestorePending = false
        restoreEpoch++
        grid.scrollToItem(0)
        snapshotFlow { headerMounted && grid.layoutInfo.visibleItemsInfo.any { it.index == 0 } }.first { it }
        withFrameNanos { }
        focus.navigationType?.let { focus.tab(it).requestFocus() }
    }
    // A confirmed tab lands on the first poster with the page at its top. The grid must have laid
    // out first, or the restore below would scroll the "unseen" poster to the top of the screen.
    LaunchedEffect(state, focus.contentEpoch) {
        val type = state.library.type
        if (focus.contentType != type) return@LaunchedEffect
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.any { it.index == COLLECTION_GRID_HEADER_ITEMS } }.first { it }
        focusedIndex = 0
        pendingFocusIndex = 0
        navigationViewportIndex = -1
        restoreToHeader = false
        directionalFocus = true
        needsFocus = true
        restoreEpoch++
        focus.completeContent(type)
    }
    LaunchedEffect(route, focus.headerEpoch, playbackRunning, modalOpen) {
        if (focus.headerRoute != route || playbackRunning || modalOpen) return@LaunchedEffect
        needsFocus = true
        restoreToHeader = true
        directionalFocus = false
        restoreEpoch++
    }

    LaunchedEffect(state.generation) {
        if (lastGeneration != state.generation) {
            lastGeneration = state.generation
            focusedIndex = state.jumpRequest?.index ?: 0
            navigationViewportIndex = -1
            pendingFocusIndex = focusedIndex
            needsFocus = focus.navigationType == null && !railHasFocus && !awaitingJump && !railRestorePending
            restoreEpoch++
            if (state.jumpRequest == null) grid.scrollToItem(0)
        }
    }
    LaunchedEffect(state.jumpRequest?.id) {
        val jump = state.jumpRequest ?: return@LaunchedEffect
        focusedIndex = jump.index
        restoreToHeader = false
        grid.scrollToCollectionLetter(jump.index + COLLECTION_GRID_HEADER_ITEMS)
        awaitingJump = false
        pendingFocusIndex = null
        if (returnAfterJump || !railHasFocus && !railRestorePending) restoreGrid()
        state.completeJump(jump.id)
    }
    LaunchedEffect(playbackRunning) {
        if (wasPlaying && !playbackRunning && focus.navigationType == null) {
            needsFocus = true
            restoreEpoch++
        }
        wasPlaying = playbackRunning
    }
    LaunchedEffect(railRestorePending, alphabet, state.alphabetLoading, state.alphabetError, playbackRunning, modalOpen) {
        if (!railRestorePending || playbackRunning || modalOpen) return@LaunchedEffect
        withFrameNanos { }
        val key = lastRailKey?.takeIf { it in railRequesters } ?: alphabet.firstOrNull()?.key
        val requester = if (state.alphabetError != null) railStatusFocus else railRequesters[key] ?: railStatusFocus
        requester.requestFocus()
        if (!state.alphabetLoading) railRestorePending = false
    }
    // No focus requests are keyed to page fills, details refreshes, or playback position reports.
    LaunchedEffect(restoreEpoch, playbackRunning, modalOpen, state.total, state.loading, focus.navigationType, focus.headerRoute, focus.contentType) {
        if (focus.navigationType != null || focus.contentType == state.library.type || focus.headerRoute?.let { it != route } == true ||
            playbackRunning || modalOpen || !needsFocus && !restoreToHeader) return@LaunchedEffect
        val focusEpoch = restoreEpoch
        if (restoreToHeader) {
            coroutineScope {
                if (directionalFocus) scroller.showHeader() else grid.scrollToItem(0)
                snapshotFlow {
                    headerMounted && grid.layoutInfo.visibleItemsInfo.any { it.index == 0 }
                }.first { it }
                withFrameNanos { }
                if (needsFocus && restoreEpoch == focusEpoch) {
                    (if (focus.headerRoute == route) {
                        if (libraries.size > 1) libraryFocus else sortFocus
                    } else sortFocus).requestFocus()
                    focus.completeHeader(route)
                }
            }
        } else if (state.total > 0 || state.loading) {
            if (!directionalFocus && navigationViewportIndex >= 0) {
                grid.scrollToItem(navigationViewportIndex, navigationViewportOffset)
                navigationViewportIndex = -1
            }
            val target = (pendingFocusIndex ?: focusedIndex).coerceIn(0, (state.total.takeIf { it > 0 } ?: 12) - 1)
            val gridIndex = target + COLLECTION_GRID_HEADER_ITEMS
            coroutineScope {
                // Directional moves are already gliding on the row scroller.
                if (!directionalFocus && grid.layoutInfo.visibleItemsInfo.none { it.index == gridIndex }) {
                    grid.scrollToItem(gridIndex)
                }
                val requester = snapshotFlow {
                    requesters[target]?.takeIf { grid.layoutInfo.visibleItemsInfo.any { it.index == gridIndex } }
                }.filterNotNull().first()
                withFrameNanos { }
                if (needsFocus && restoreEpoch == focusEpoch) {
                    focusedIndex = target
                    requester.requestFocus()
                }
            }
        } else {
            grid.scrollToItem(0)
            snapshotFlow { headerMounted }.first { it }
            withFrameNanos { }
            if (restoreEpoch != focusEpoch) return@LaunchedEffect
            backFocus.requestFocus()
        }
        if (restoreEpoch != focusEpoch) return@LaunchedEffect
        pendingFocusIndex = null
        needsFocus = false
        restoreToHeader = false
        directionalFocus = false
    }

    BackHandler(enabled = !playbackRunning && selected == null) {
        when {
            sortOpen -> {
                sortOpen = false
                restoreToHeader = true
                needsFocus = true
                restoreEpoch++
            }
            railHasFocus -> leaveAlphabet()
            else -> onBack()
        }
    }

    // MovieBackdrop paints the screen's background together with its wash.
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (selected != null) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        val compact = maxWidth < 700.dp
        val edge = if (compact) 24.dp else 40.dp
        val railWidth = if (compact) 56.dp else 64.dp
        val gridWidth = maxWidth - railWidth
        val columns = when {
            gridWidth >= 1500.dp -> 7
            gridWidth >= 1000.dp -> 6
            gridWidth >= 780.dp -> 5
            gridWidth >= 600.dp -> 4
            gridWidth >= 420.dp -> 3
            else -> 2
        }
        val density = LocalDensity.current
        val posterPixels = with(density) {
            ((gridWidth - edge - 18.dp - 18.dp * (columns - 1)) / columns).toPx()
        }
        val artworkWidth = artworkBucket(posterPixels)
        val rowSpacing = with(density) { 24.dp.toPx() }
        SideEffect {
            scroller.columns = columns
            scroller.spacing = rowSpacing
        }
        val emptyHeight = (maxHeight - 180.dp).coerceAtLeast(260.dp)
        val controlsEnabled = !playbackRunning && !modalOpen
        val lastIndex = (if (state.total > 0) state.total else 12) - 1
        // Derived so a focus move only recomposes this screen when the rail actually appears or hides.
        val showAlphabet by remember(columns, lastIndex, controlsEnabled) {
            derivedStateOf {
                val atRightEdge = focusedIndex % columns == columns - 1 || focusedIndex == lastIndex
                controlsEnabled && (railHasFocus || railRestorePending || awaitingJump || gridHasFocus && atRightEdge)
            }
        }
        val railOpacity = animateFloatAsState(if (showAlphabet) 1f else 0f, tween(150), label = "alphabet reveal")

        fun enterAlphabet() {
            lastRailKey = lastRailKey?.takeIf { it in railRequesters }
                ?: (if (state.sort == CollectionSort.TITLE) alphabet.lastOrNull { it.start <= focusedIndex }?.key else null)
                ?: alphabet.firstOrNull()?.key
            needsFocus = false
            pendingFocusIndex = null
            restoreEpoch++
            if (showAlphabet) {
                val target = if (state.alphabetError != null) railStatusFocus
                    else railRequesters[lastRailKey] ?: railStatusFocus
                target.requestFocus()
                railRestorePending = alphabet.isEmpty() && state.alphabetLoading
            } else {
                railRestorePending = true
            }
        }

        MovieBackdrop(
            movie = { state.itemAt(focusedIndex) },
            posters = posters,
            artworkWidth = artworkWidth,
            active = !playbackRunning,
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(state, state.generation, columns, artworkWidth, playbackRunning) {
            if (playbackRunning) return@LaunchedEffect
            var previousFirst = 0
            var forward = true
            snapshotFlow {
                val visible = grid.layoutInfo.visibleItemsInfo
                val firstMovie = visible.firstOrNull { it.index >= COLLECTION_GRID_HEADER_ITEMS }
                val lastMovie = visible.lastOrNull { it.index >= COLLECTION_GRID_HEADER_ITEMS }
                if (firstMovie == null || lastMovie == null || state.total == 0 && !state.loading) null else {
                    // Lazy index zero is chrome, never a Plex collection offset.
                    ((firstMovie.index - COLLECTION_GRID_HEADER_ITEMS) / columns * columns) to
                        (((lastMovie.index - COLLECTION_GRID_HEADER_ITEMS) / columns + 1) * columns - 1)
                }
            }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
                artworkRange = (first - columns).coerceAtLeast(0)..(last + columns)
                if (first != previousFirst) forward = first > previousFirst
                previousFirst = first
                val distance = columns * if (artworkWidth <= 384) 3 else 2
                lookahead = if (forward) last + 1..minOf(last + distance, state.total - 1)
                    else first - 1 downTo (first - distance).coerceAtLeast(0)
                // The state owns metadata prefetch (one page behind / two ahead) and its bounded cache.
                state.requestRange(first, last.coerceAtMost((state.total - 1).coerceAtLeast(0)))
            }
        }

        LaunchedEffect(state, state.generation, posters, artworkWidth, playbackRunning) {
            if (playbackRunning) return@LaunchedEffect
            val height = artworkWidth * 3 / 2
            snapshotFlow {
                if (sortOpen || state.selectedMovie != null || railHasFocus) emptyList() else
                    lookahead.mapNotNull { state.itemAt(it)?.thumbPath }.distinct()
            }.distinctUntilChanged().collectLatest { paths ->
                if (paths.isEmpty()) return@collectLatest
                repeat(2) { withFrameNanos { } }
                // One speculative image at a time, exact display dimensions, nearest row first.
                for (path in paths) {
                    if (posters.cached(path, artworkWidth, height) != null) continue
                    posters.awaitIdle()
                    try {
                        posters.load(path, artworkWidth, height)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        // A visible card can retry later; speculative failures aren't screen errors.
                    }
                    withFrameNanos { }
                }
            }
        }

        Row(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides gridBringIntoView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    contentPadding = PaddingValues(start = edge, end = 18.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .onFocusChanged { if (!it.hasFocus) gridHasFocus = false }.focusGroup(),
                ) {
                    item(key = "collection-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        DisposableEffect(Unit) {
                            headerMounted = true
                            onDispose { headerMounted = false }
                        }
                        CollectionHeader(
                            state = state,
                            navigation = navigation,
                            libraryFocus = libraryFocus,
                            libraries = libraries,
                            onLibrary = onLibrary,
                            onLibraryDismiss = { focus.enterHeader(route) },
                            error = error,
                            compact = compact,
                            enabled = controlsEnabled,
                            backFocus = backFocus,
                            sortFocus = sortFocus,
                            onBack = onBack,
                            onSort = { restoreGrid(); sortOpen = true },
                            onToggleUnwatched = { restoreGrid(); state.setUnwatchedOnly(!state.unwatchedOnly) },
                            modifier = Modifier.onFocusChanged {
                                if (it.hasFocus) {
                                    gridHasFocus = false
                                    if (controlsEnabled && focus.navigationType == null) revealHeader()
                                } else if (restoreToHeader && !needsFocus) {
                                    restoreToHeader = false
                                    directionalFocus = false
                                    restoreEpoch++
                                }
                            }.onPreviewKeyEvent { event ->
                                if (controlsEnabled && focus.navigationType == null && event.key == Key.DirectionDown) {
                                    if (event.type == KeyEventType.KeyDown) restoreGrid()
                                    true
                                } else {
                                    if (controlsEnabled && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown)
                                        revealHeader()
                                    false
                                }
                            }.focusGroup(),
                        )
                    }
                    if (state.total == 0 && !state.loading) {
                        item(key = "collection-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            Column(
                                Modifier.fillMaxWidth().height(emptyHeight),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(if (state.error != null) "Your library couldn't be loaded" else if (state.unwatchedOnly) "All caught up" else "No ${state.noun}s here yet",
                                    color = CinemaColors.Foreground, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(14.dp))
                                Text(state.error ?: if (state.unwatchedOnly) "Turn off Unwatched to see your whole collection." else "${state.title} added to this Plex library will appear here.",
                                    color = CinemaColors.Muted, fontSize = 18.sp, modifier = Modifier.widthIn(max = 600.dp))
                                Spacer(Modifier.height(24.dp))
                                if (state.error != null) CinemaButton(onClick = state::retry, enabled = controlsEnabled) { ControlText("Try again") }
                                else if (state.unwatchedOnly) CinemaButton(
                                    onClick = { restoreGrid(); state.setUnwatchedOnly(false) }, enabled = controlsEnabled,
                                ) { ControlText("Show all ${state.noun}s") }
                            }
                        }
                    } else {
                        items(
                            count = if (state.total > 0) state.total else 12,
                            key = { index -> (state.generation.toLong() shl 32) or index.toLong() },
                            contentType = { "collection-poster" },
                        ) { index ->
                            val item = if (state.total > 0) state.itemAt(index) else null
                            val requester = remember(state.generation, index) { FocusRequester() }
                            val inArtworkRange by remember(state.generation, index) { derivedStateOf { index in artworkRange } }
                            DisposableEffect(requester) {
                                requesters[index] = requester
                                onDispose { requesters.remove(index) }
                            }
                            CollectionPosterCard(
                                item = item,
                                posters = posters,
                                artworkWidth = artworkWidth,
                                loadArtwork = !playbackRunning && inArtworkRange,
                                enabled = controlsEnabled,
                                modifier = Modifier.focusRequester(requester).onPreviewKeyEvent { event ->
                                    val count = if (state.total > 0) state.total else 12
                                    val logicalIndex = pendingFocusIndex ?: index
                                    val column = logicalIndex % columns
                                    val rowEnd = column == columns - 1 || logicalIndex == count - 1
                                    if (controlsEnabled && restoreToHeader && event.key == Key.DirectionUp) {
                                        // Repeats can still reach the old poster before header focus attaches.
                                        true
                                    } else if (event.key == Key.DirectionRight && rowEnd && controlsEnabled) {
                                        if (event.type == KeyEventType.KeyDown) enterAlphabet()
                                        true
                                    } else if (controlsEnabled && event.key == Key.DirectionDown) {
                                        val nextRow = (logicalIndex / columns + 1) * columns
                                        if (event.type == KeyEventType.KeyDown && nextRow < count)
                                            movePosterFocus((logicalIndex + columns).coerceAtMost(count - 1))
                                        true
                                    } else if (controlsEnabled && event.key == Key.DirectionUp && logicalIndex >= columns) {
                                        if (event.type == KeyEventType.KeyDown) movePosterFocus(logicalIndex - columns)
                                        true
                                    } else if (controlsEnabled && event.key == Key.DirectionUp && pendingFocusIndex != null) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            pendingFocusIndex = null
                                            directionalFocus = true
                                            restoreToHeader = true
                                            needsFocus = true
                                            restoreEpoch++
                                        }
                                        true
                                    } else if (controlsEnabled && pendingFocusIndex != null &&
                                        (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                                        val target = if (event.key == Key.DirectionLeft && column > 0) logicalIndex - 1
                                            else if (event.key == Key.DirectionRight && !rowEnd) logicalIndex + 1 else logicalIndex
                                        if (event.type == KeyEventType.KeyDown && target != logicalIndex) movePosterFocus(target)
                                        true
                                    } else false
                                },
                                onFocus = {
                                    if (controlsEnabled && !needsFocus && pendingFocusIndex == null) focusedIndex = index
                                    gridHasFocus = true
                                    railRestorePending = false
                                },
                                onClick = {
                                    focusedIndex = index
                                    railRestorePending = false
                                    if (item != null) {
                                        if (state.isShow) {
                                            // Pin selection before child navigation disposes the grid.
                                            needsFocus = true
                                            onOpenShow(item)
                                        } else {
                                            detailsIndex = index
                                            state.selectMovie(item)
                                        }
                                    } else state.requestRange(index, index)
                                },
                                placeholderLabel = "${state.noun.replaceFirstChar { it.uppercase() }} ${index + 1}",
                            )
                        }
                    }
                }
            }
            CollectionAlphabetRail(
                letters = alphabet,
                noun = state.noun,
                loading = state.alphabetLoading,
                error = state.alphabetError,
                enabled = controlsEnabled && showAlphabet,
                titleSorted = state.sort == CollectionSort.TITLE,
                requesters = railRequesters,
                statusFocus = railStatusFocus,
                onFocusChanged = { railHasFocus = it },
                onExit = ::leaveAlphabet,
                onRetry = {
                    railRestorePending = true
                    state.retryAlphabet()
                },
                onChoose = { leaveAlphabet() },
                onLetterFocus = { key ->
                    lastRailKey = key
                    awaitingJump = true
                    returnAfterJump = false
                    needsFocus = false
                    railRestorePending = false
                    state.jumpToLetter(key)
                },
                modifier = Modifier.width(railWidth).fillMaxHeight()
                    .graphicsLayer {
                        alpha = railOpacity.value
                        translationX = (1f - railOpacity.value) * 8.dp.toPx()
                    }
                    .then(if (showAlphabet) Modifier else Modifier.clearAndSetSemantics { })
                    .padding(top = 16.dp, end = if (compact) 12.dp else 20.dp, bottom = 16.dp),
            )
        }
    }

    if (sortOpen) {
        CollectionSortDialog(
            current = state.sort,
            onDismiss = {
                sortOpen = false
                restoreToHeader = true
                needsFocus = true
                restoreEpoch++
            },
            onChoose = {
                state.setSort(it)
                sortOpen = false
                restoreToHeader = true
                needsFocus = true
                restoreEpoch++
            },
        )
    }
    if (selected != null) {
        MovieDetailsScreen(
            movie = selected,
            posters = posters,
            loading = state.detailsLoading,
            error = state.detailsError,
            enabled = !playbackRunning,
            onDismiss = ::closeDetails,
            onRetry = { state.selectMovie(selected) },
            onPlay = { fromBeginning ->
                closeDetails()
                onPlay(selected, fromBeginning)
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionHeader(
    state: CollectionLibraryState,
    navigation: @Composable () -> Unit,
    libraryFocus: FocusRequester,
    libraries: List<PlexLibrary>,
    onLibrary: (PlexLibrary) -> Unit,
    onLibraryDismiss: () -> Unit,
    error: String?,
    compact: Boolean,
    enabled: Boolean,
    backFocus: FocusRequester,
    sortFocus: FocusRequester,
    onBack: () -> Unit,
    onSort: () -> Unit,
    onToggleUnwatched: () -> Unit,
    modifier: Modifier,
) {
    CollectionHeaderChrome(
        title = "",
        subtitle = "",
        libraryControl = { modifier ->
            CollectionLibraryControl(
                library = state.library,
                libraries = libraries,
                enabled = enabled,
                modifier = modifier.focusRequester(libraryFocus),
                onChoose = onLibrary,
                onDismiss = onLibraryDismiss,
            )
        },
        count = when {
            state.loading -> "Loading your library…"
            state.unwatchedOnly -> "${state.total} unwatched ${state.noun}s"
            else -> "${state.total} ${state.noun}s"
        },
        hint = if (state.isShow) "Select a show for seasons" else "Select a movie for details",
        navigation = navigation,
        compact = compact,
        enabled = enabled,
        backFocus = backFocus,
        onBack = onBack,
        modifier = modifier,
        actions = {
            CinemaButton(onClick = onSort, modifier = Modifier.focusRequester(sortFocus), enabled = enabled) {
                ControlText("Sort · ${state.sort.title}${if (compact) "" else "  ⌄"}")
            }
            CinemaButton(onClick = onToggleUnwatched, enabled = enabled, selected = state.unwatchedOnly) {
                ControlText(if (state.unwatchedOnly) "Unwatched · On" else "Unwatched")
            }
        },
    ) {
        if (error != null) ErrorNote(error, Modifier.padding(top = 12.dp))
        if (state.error != null && state.total > 0) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ErrorNote(state.error.orEmpty(), Modifier.weight(1f))
                CinemaButton(onClick = state::retry, enabled = enabled) { ControlText("Retry") }
            }
        }
    }
}

/** Scrolling chrome shares library selection, counts and control positions without a giant type title. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionHeaderChrome(
    title: String,
    subtitle: String,
    count: String,
    hint: String?,
    navigation: @Composable () -> Unit,
    libraryControl: @Composable (Modifier) -> Unit,
    compact: Boolean,
    enabled: Boolean,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "‹  Sources",
    actions: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        navigation()
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CinemaButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus), enabled = enabled) {
                ControlText(backLabel)
            }
            Column(Modifier.weight(1f)) {
                libraryControl(Modifier)
                if (title.isNotBlank()) Text(
                    listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                    color = CinemaColors.Muted, fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
            if (!compact) actions()
        }
        if (compact) {
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(count, color = CinemaColors.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (!compact && hint != null) Text(hint, color = CinemaColors.Muted.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        footer()
    }
}

/** Server-supplied buckets retain their labels/order, including digits and non-Latin scripts. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionAlphabetRail(
    letters: List<CollectionLetter>,
    noun: String,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    titleSorted: Boolean,
    requesters: Map<String, FocusRequester>,
    statusFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (CollectionLetter) -> Unit,
    onLetterFocus: (String) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier.onFocusChanged { onFocusChanged(it.hasFocus) }.focusGroup()
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown) onExit()
                        true
                    }
                    Key.DirectionRight -> true
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "A–Z", color = CinemaColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp).semantics {
                contentDescription = if (titleSorted) "Focus a letter to jump by title" else "Focus a letter to switch sort to title and jump."
            },
        )
        Text(if (titleSorted) "Jump" else "Sort", color = CinemaColors.Muted, fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val statusSpace = if (error != null || loading && letters.isNotEmpty()) 48.dp else 0.dp
            val minimumCellHeight = with(LocalDensity.current) { 16.sp.toDp() }
            val cellHeight = ((maxHeight - 8.dp - statusSpace) / letters.size.coerceAtLeast(1))
                .coerceIn(minimumCellHeight, 30.dp.coerceAtLeast(minimumCellHeight))
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (error != null) {
                    Text("Unavailable", color = CinemaColors.Error, fontSize = 10.sp, maxLines = 2,
                        modifier = Modifier.semantics {
                            contentDescription = "Title index could not be loaded. $error"
                            liveRegion = LiveRegionMode.Polite
                        })
                }
                if (letters.isEmpty() || error != null) {
                    CollectionLetterButton(
                        label = if (error != null) "Retry" else if (loading) "Loading" else "None",
                        description = if (error != null) "Retry title index" else if (loading) "Loading title index" else "No title groups available",
                        enabled = enabled,
                        onClick = if (error != null) onRetry else null,
                        onFocus = {},
                        modifier = Modifier.focusRequester(statusFocus).focusProperties {
                            up = FocusRequester.Cancel
                            down = letters.firstOrNull()?.let { requesters[it.key] } ?: FocusRequester.Cancel
                        }.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (loading && letters.isNotEmpty()) {
                    Text("Updating…", color = CinemaColors.Muted, fontSize = 10.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                letters.forEachIndexed { index, letter ->
                    androidx.compose.runtime.key(letter.key) {
                        CollectionLetterButton(
                            label = letter.title,
                            description = "Jump to ${letter.title}, ${letter.count} ${noun}s" +
                                if (titleSorted) "" else ". Sorts by title",
                            enabled = enabled,
                            height = cellHeight,
                            onClick = { onChoose(letter) },
                            onFocus = { onLetterFocus(letter.key) },
                            modifier = Modifier.focusRequester(requesters.getValue(letter.key)).focusProperties {
                                up = if (index > 0) requesters.getValue(letters[index - 1].key)
                                    else if (error != null) statusFocus else FocusRequester.Cancel
                                down = if (index < letters.lastIndex) requesters.getValue(letters[index + 1].key)
                                    else FocusRequester.Cancel
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionLetterButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    onFocus: () -> Unit,
    modifier: Modifier,
    height: Dp = 32.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val compactLabel = height < with(LocalDensity.current) { 24.sp.toDp() }
    val fill = animateFloatAsState(if (focused) 0.25f else 0f, tween(140), label = "letter fill")
    val scale = animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "letter focus")
    LaunchedEffect(focused) {
        if (focused) bringIntoView.bringIntoView()
    }
    Box(
        modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (enabled) 1f else 0.45f
            }.clip(RoundedCornerShape(10.dp)).drawWithCache {
                val sheen = Brush.verticalGradient(
                    listOf(CinemaColors.Foreground.copy(alpha = 0.045f), Color.Transparent),
                )
                onDrawBehind {
                    drawRect(CinemaColors.Foreground, alpha = fill.value)
                    drawRect(sheen, alpha = fill.value * 4)
                }
            }.then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick,
                ) else Modifier.focusable(enabled),
            ).semantics { contentDescription = description }
            .height(height).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) CinemaColors.Foreground else CinemaColors.Muted,
            fontSize = if (label.length > 3 || compactLabel) 12.sp else 15.sp,
            lineHeight = if (compactLabel) 14.sp else 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlText(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorNote(message: String, modifier: Modifier = Modifier) {
    Text(message, color = CinemaColors.Error, fontSize = 16.sp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionSortDialog(current: CollectionSort, onDismiss: () -> Unit, onChoose: (CollectionSort) -> Unit) {
    val initial = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopEnd) {
            Column(
                Modifier.padding(top = 70.dp).widthIn(max = 340.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.verticalGradient(listOf(CinemaColors.Panel.copy(alpha = 0.98f), CinemaColors.Canvas.copy(alpha = 0.98f))))
                    .glassRim(30.dp)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("SORT YOUR LIBRARY", color = CinemaColors.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                CollectionSort.entries.forEach { sort ->
                    CinemaButton(onClick = { onChoose(sort) }, selected = current == sort,
                        modifier = Modifier.fillMaxWidth().then(if (current == sort) Modifier.focusRequester(initial) else Modifier)) {
                        ControlText(sort.title)
                        Spacer(Modifier.weight(1f))
                        if (current == sort) Text("Selected", fontSize = 12.sp)
                    }
                }
                CinemaButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { ControlText("Cancel") }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            initial.requestFocus()
        }
    }
}


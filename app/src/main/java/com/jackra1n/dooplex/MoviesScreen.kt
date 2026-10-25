package com.jackra1n.dooplex

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The mounted grid retains its scroll offset across details/playback. The alphabet's gutter stays
 * reserved while its controls fade, so reaching the right edge never resizes or repaginates posters.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviesScreen(
    state: MovieLibraryState,
    posters: PosterRepository,
    accountName: String,
    playbackRunning: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
) {
    val grid = rememberLazyGridState()
    // Only the scroll start/stop boundary invalidates artwork gates, never the pixel offset.
    val scrolling by remember(grid) { derivedStateOf { grid.isScrollInProgress } }
    val requesters = remember(state, state.generation) { mutableStateMapOf<Int, FocusRequester>() }
    val sortFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var focusedIndex by rememberSaveable(state.library.key) { mutableIntStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    var restoreToSort by remember { mutableStateOf(false) }
    var restoreEpoch by remember { mutableIntStateOf(0) }
    var needsFocus by remember { mutableStateOf(true) }
    var wasPlaying by remember { mutableStateOf(playbackRunning) }
    var lastGeneration by remember(state) { mutableIntStateOf(state.generation) }
    var artworkRange by remember(state.generation) { mutableStateOf(IntRange.EMPTY) }
    var railHasFocus by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var awaitingJump by remember { mutableStateOf(false) }
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
        restoreToSort = false
        awaitingJump = false
        railRestorePending = false
        needsFocus = true
        restoreEpoch++
    }

    fun closeDetails() {
        state.closeDetails()
        restoreGrid()
    }

    LaunchedEffect(state.generation) {
        if (lastGeneration != state.generation) {
            lastGeneration = state.generation
            focusedIndex = state.jumpRequest?.index ?: 0
            needsFocus = !railHasFocus && !awaitingJump && !railRestorePending
            restoreEpoch++
            if (state.jumpRequest == null) grid.scrollToItem(0)
        }
    }
    LaunchedEffect(state.jumpRequest?.id) {
        val jump = state.jumpRequest ?: return@LaunchedEffect
        focusedIndex = jump.index
        restoreToSort = false
        // The server gives an absolute title-sort index; jump directly, including uncached pages.
        grid.scrollToItem(jump.index)
        restoreGrid()
    }
    LaunchedEffect(playbackRunning) {
        if (wasPlaying && !playbackRunning) {
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
    LaunchedEffect(restoreEpoch, playbackRunning, modalOpen, state.total, state.loading) {
        if (playbackRunning || modalOpen ||
            (!needsFocus && (state.total > 0 || state.loading || railHasFocus || awaitingJump || railRestorePending))) return@LaunchedEffect
        if (restoreToSort) {
            withFrameNanos { }
            sortFocus.requestFocus()
        } else if (state.total > 0 || state.loading) {
            val target = focusedIndex.coerceIn(0, (state.total.takeIf { it > 0 } ?: 12) - 1)
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target)
            val requester = snapshotFlow { requesters[target] }.filterNotNull().first()
            withFrameNanos { }
            requester.requestFocus()
        } else {
            withFrameNanos { }
            backFocus.requestFocus()
        }
        needsFocus = false
    }

    BackHandler(enabled = !playbackRunning) {
        when {
            sortOpen -> {
                sortOpen = false
                restoreToSort = true
                needsFocus = true
                restoreEpoch++
            }
            selected != null -> closeDetails()
            railHasFocus -> restoreGrid()
            else -> onBack()
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(CinemaColors.Panel, CinemaColors.Canvas, CinemaColors.Canvas)),
        ).windowInsetsPadding(WindowInsets.safeDrawing),
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
        val controlsEnabled = !playbackRunning && !modalOpen
        val lastIndex = (if (state.total > 0) state.total else 12) - 1
        val atRightEdge = focusedIndex % columns == columns - 1 || focusedIndex == lastIndex
        val showAlphabet = controlsEnabled &&
            (railHasFocus || railRestorePending || awaitingJump || gridHasFocus && atRightEdge)
        val railOpacity = animateFloatAsState(if (showAlphabet) 1f else 0f, tween(150), label = "alphabet reveal")

        fun enterAlphabet() {
            lastRailKey = lastRailKey?.takeIf { it in railRequesters }
                ?: (if (state.sort == MovieSort.TITLE) alphabet.lastOrNull { it.start <= focusedIndex }?.key else null)
                ?: alphabet.firstOrNull()?.key
            needsFocus = false
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
            movie = state.itemAt(focusedIndex),
            posters = posters,
            artworkWidth = artworkWidth,
            active = !scrolling && !playbackRunning,
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(state, state.generation, columns, playbackRunning) {
            if (playbackRunning) return@LaunchedEffect
            snapshotFlow {
                val visible = grid.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) null else {
                    // Row buckets change only when content enters/leaves the viewport, not per pixel.
                    (visible.first().index / columns * columns) to
                        ((visible.last().index / columns + 1) * columns - 1)
                }
            }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
                artworkRange = (first - columns).coerceAtLeast(0)..(last + columns)
                // The state owns metadata prefetch (one page behind / two ahead) and its bounded cache.
                state.requestRange(first, last.coerceAtMost((state.total - 1).coerceAtLeast(0)))
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = edge, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DOOPLEX", color = CinemaColors.Accent, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text(accountName, color = CinemaColors.Muted, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = edge).padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CinemaButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus), enabled = !playbackRunning && !modalOpen) {
                    ControlText("‹  Libraries")
                }
                Column(Modifier.weight(1f)) {
                    Text("Movies", color = CinemaColors.Foreground, fontSize = if (compact) 28.sp else 34.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(state.library.title, color = CinemaColors.Muted, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!compact) {
                    CinemaButton(
                        onClick = { restoreGrid(); sortOpen = true },
                        modifier = Modifier.focusRequester(sortFocus),
                        enabled = !playbackRunning && !modalOpen,
                    ) { ControlText("Sort · ${state.sort.title}  ⌄") }
                    CinemaButton(onClick = { restoreGrid(); state.setUnwatchedOnly(!state.unwatchedOnly) },
                        enabled = !playbackRunning && !modalOpen, selected = state.unwatchedOnly) {
                        ControlText(if (state.unwatchedOnly) "Unwatched · On" else "Unwatched")
                    }
                }
            }
            if (compact) {
                Row(Modifier.padding(horizontal = edge).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CinemaButton(onClick = { restoreGrid(); sortOpen = true }, modifier = Modifier.focusRequester(sortFocus),
                        enabled = !playbackRunning && !modalOpen) { ControlText("Sort · ${state.sort.title}") }
                    CinemaButton(onClick = { restoreGrid(); state.setUnwatchedOnly(!state.unwatchedOnly) },
                        enabled = !playbackRunning && !modalOpen, selected = state.unwatchedOnly) {
                        ControlText(if (state.unwatchedOnly) "Unwatched · On" else "Unwatched")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = edge).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.loading -> "Loading your library…"
                        state.unwatchedOnly -> "${state.total} unwatched movies"
                        else -> "${state.total} movies"
                    },
                    color = CinemaColors.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f),
                )
                if (!compact) Text("Select a movie for details", color = CinemaColors.Muted.copy(alpha = 0.8f), fontSize = 14.sp)
            }
            if (error != null) {
                ErrorNote(error, Modifier.padding(horizontal = edge).padding(bottom = 12.dp))
            }
            if (state.error != null && state.total > 0) {
                Row(Modifier.padding(horizontal = edge).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ErrorNote(state.error.orEmpty(), Modifier.weight(1f))
                    CinemaButton(onClick = state::retry, enabled = !playbackRunning && !modalOpen) { ControlText("Retry") }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
            if (state.total == 0 && !state.loading) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(edge),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (state.error != null) "Your library couldn't be loaded" else if (state.unwatchedOnly) "All caught up" else "No movies here yet",
                        color = CinemaColors.Foreground, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    Text(state.error ?: if (state.unwatchedOnly) "Turn off Unwatched to see your whole collection." else "Movies added to this Plex library will appear here.",
                        color = CinemaColors.Muted, fontSize = 18.sp, modifier = Modifier.widthIn(max = 600.dp))
                    Spacer(Modifier.height(24.dp))
                    if (state.error != null) CinemaButton(onClick = state::retry, enabled = !playbackRunning && !modalOpen) { ControlText("Try again") }
                    else if (state.unwatchedOnly) CinemaButton(onClick = { restoreGrid(); state.setUnwatchedOnly(false) }, enabled = !playbackRunning && !modalOpen) { ControlText("Show all movies") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    contentPadding = PaddingValues(start = edge, end = 18.dp, top = 7.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .onFocusChanged { gridHasFocus = it.hasFocus }.focusGroup(),
                ) {
                    items(
                        count = if (state.total > 0) state.total else 12,
                        key = { index -> (state.generation.toLong() shl 32) or index.toLong() },
                        contentType = { "movie-poster" },
                    ) { index ->
                        val item = if (state.total > 0) state.itemAt(index) else null
                        val requester = remember(state.generation, index) { FocusRequester() }
                        DisposableEffect(requester) {
                            requesters[index] = requester
                            onDispose { requesters.remove(index) }
                        }
                        MoviePosterCard(
                            movie = item,
                            posters = posters,
                            artworkWidth = artworkWidth,
                            loadArtwork = !playbackRunning && !scrolling && index in artworkRange,
                            enabled = !playbackRunning && !modalOpen,
                            modifier = Modifier.focusRequester(requester).onPreviewKeyEvent { event ->
                                val count = if (state.total > 0) state.total else 12
                                val rowEnd = index % columns == columns - 1 || index == count - 1
                                if (event.key == Key.DirectionRight && rowEnd && controlsEnabled) {
                                    if (event.type == KeyEventType.KeyDown) enterAlphabet()
                                    true
                                } else false
                            },
                            onFocus = {
                                focusedIndex = index
                                railRestorePending = false
                            },
                            onClick = {
                                focusedIndex = index
                                railRestorePending = false
                                if (item != null) state.selectMovie(item) else state.requestRange(index, index)
                            },
                            placeholderLabel = "Movie ${index + 1}",
                        )
                    }
                }
            }
                MovieAlphabetRail(
                    letters = alphabet,
                    loading = state.alphabetLoading,
                    error = state.alphabetError,
                    enabled = controlsEnabled && showAlphabet,
                    titleSorted = state.sort == MovieSort.TITLE,
                    requesters = railRequesters,
                    statusFocus = railStatusFocus,
                    onFocusChanged = { railHasFocus = it },
                    onExit = ::restoreGrid,
                    onRetry = {
                        railRestorePending = true
                        state.retryAlphabet()
                    },
                    onChoose = { letter ->
                        lastRailKey = letter.key
                        awaitingJump = true
                        railRestorePending = false
                        state.jumpToLetter(letter.key)
                    },
                    onLetterFocus = { lastRailKey = it },
                    modifier = Modifier.width(railWidth).fillMaxHeight()
                        .graphicsLayer {
                            alpha = railOpacity.value
                            translationX = (1f - railOpacity.value) * 8.dp.toPx()
                        }
                        .then(if (showAlphabet) Modifier else Modifier.clearAndSetSemantics { })
                        .padding(end = if (compact) 12.dp else 20.dp, bottom = 16.dp),
                )
            }
        }
    }

    if (sortOpen) {
        MovieSortDialog(
            current = state.sort,
            onDismiss = {
                sortOpen = false
                restoreToSort = true
                needsFocus = true
                restoreEpoch++
            },
            onChoose = {
                state.setSort(it)
                sortOpen = false
                restoreToSort = true
                needsFocus = true
                restoreEpoch++
            },
        )
    }
    if (selected != null) {
        MovieDetailsDialog(
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

/** Server-supplied buckets retain their labels/order, including digits and non-Latin scripts. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieAlphabetRail(
    letters: List<MovieLetter>,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    titleSorted: Boolean,
    requesters: Map<String, FocusRequester>,
    statusFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (MovieLetter) -> Unit,
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
                contentDescription = if (titleSorted) "Jump by title" else "Jump by title. Choosing a letter switches sort to title."
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
                    MovieLetterButton(
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
                        MovieLetterButton(
                            label = letter.title,
                            description = "Jump to ${letter.title}, ${letter.count} movies" +
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
private fun MovieLetterButton(
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

private fun artworkBucket(pixels: Float): Int = when {
    pixels <= 192 -> 192
    pixels <= 256 -> 256
    pixels <= 384 -> 384
    else -> 512
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlText(text: String) {
    Text(text, color = CinemaColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium,
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
private fun MoviePosterCard(
    movie: PlexBrowseItem?,
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
    val scale = animateFloatAsState(if (focused) 1.035f else 1f, tween(160), label = "poster focus")
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "poster highlight")
    Column(
        modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick,
        ).semantics {
            contentDescription = if (movie == null) "$placeholderLabel, loading. Select to request this movie."
                else listOfNotNull(movie.title, movie.subtitle.takeIf { it.isNotBlank() },
                    if (movie.watched) "Watched" else if (movie.viewOffsetMs > 0) "Partially watched" else null).joinToString(", ")
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)
            .background(CinemaColors.Panel)
            .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.07f), shape)
            .drawWithCache {
                val highlight = Brush.radialGradient(
                    colors = listOf(CinemaColors.Foreground.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.height.coerceAtLeast(1f),
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(highlight, alpha = light.value)
                }
            }) {
            PosterArtwork(movie?.thumbPath, posters, artworkWidth, loadArtwork,
                Modifier.fillMaxSize(), movie?.title ?: placeholderLabel, metadataLoading = movie == null)
            if (movie?.watched == true) {
                Text("WATCHED", color = CinemaColors.Foreground, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .clip(RoundedCornerShape(5.dp)).background(CinemaColors.Canvas.copy(alpha = 0.88f))
                        .padding(horizontal = 7.dp, vertical = 5.dp))
            }
            if (movie != null && movie.viewOffsetMs > 0 && movie.durationMs > 0 && !movie.watched) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(CinemaColors.Canvas.copy(alpha = 0.8f))) {
                    Box(Modifier.fillMaxWidth((movie.viewOffsetMs.toDouble() / movie.durationMs).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().background(CinemaColors.Accent))
                }
            }
        }
        Text(movie?.title ?: "Loading…", color = if (focused) CinemaColors.Foreground else CinemaColors.Foreground.copy(alpha = 0.85f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(movie?.subtitle?.ifBlank { " " } ?: " ", color = CinemaColors.Muted, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PosterArtwork(
    path: String?,
    posters: PosterRepository,
    width: Int,
    load: Boolean,
    modifier: Modifier,
    title: String,
    metadataLoading: Boolean = false,
) {
    val height = width * 3 / 2
    val cached = remember(posters, path, width) { path?.let { posters.cached(it, width, height) } }
    var bitmap by remember(posters, path, width) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(posters, path, width, load) {
        if (path == null) return@LaunchedEffect
        posters.cached(path, width, height)?.let { bitmap = it }
        // Retain already displayed images while scrolling; only cache misses wait for idle.
        if (!load || bitmap != null) return@LaunchedEffect
        try {
            bitmap = posters.load(path, width, height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            // Keep a stable, readable artwork fallback; a later viewport visit can retry.
        }
    }
    val painter = remember(bitmap) { bitmap?.let { BitmapPainter(it.asImageBitmap()) } }
    if (painter != null) {
        Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.verticalGradient(listOf(CinemaColors.Panel, CinemaColors.Canvas))),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(title.take(1).uppercase(), color = CinemaColors.Accent.copy(alpha = 0.45f),
                    fontSize = 44.sp, fontWeight = FontWeight.Light)
                if (path == null && !metadataLoading) Text("NO ARTWORK", color = CinemaColors.Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieSortDialog(current: MovieSort, onDismiss: () -> Unit, onChoose: (MovieSort) -> Unit) {
    val initial = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopEnd) {
            Column(
                Modifier.padding(top = 70.dp).widthIn(max = 340.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.verticalGradient(listOf(CinemaColors.Panel.copy(alpha = 0.98f), CinemaColors.Canvas.copy(alpha = 0.98f))))
                    .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.075f), RoundedCornerShape(30.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("SORT YOUR LIBRARY", color = CinemaColors.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                MovieSort.entries.forEach { sort ->
                    CinemaButton(onClick = { onChoose(sort) }, selected = current == sort,
                        modifier = Modifier.fillMaxWidth().then(if (current == sort) Modifier.focusRequester(initial) else Modifier)) {
                        ControlText(sort.title)
                        Spacer(Modifier.weight(1f))
                        if (current == sort) Text("Selected", color = CinemaColors.Accent, fontSize = 12.sp)
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieDetailsDialog(
    movie: PlexBrowseItem,
    posters: PosterRepository,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Boolean) -> Unit,
) {
    val playFocus = remember(movie.ratingKey) { FocusRequester() }
    Dialog(onDismissRequest = { if (enabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 650.dp
            val posterWidth = if (wide) 210.dp else 120.dp
            val bucket = artworkBucket(with(LocalDensity.current) { posterWidth.toPx() })
            Column(Modifier.widthIn(max = 980.dp).fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(CinemaColors.Panel.copy(alpha = 0.98f), CinemaColors.Canvas.copy(alpha = 0.98f))))
                .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.075f), RoundedCornerShape(32.dp))
                .verticalScroll(rememberScrollState()).padding(if (wide) 32.dp else 22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 30.dp else 20.dp)) {
                    PosterArtwork(movie.thumbPath, posters, bucket, enabled,
                        Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), movie.title)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("YOUR COLLECTION", color = CinemaColors.Accent, fontSize = 12.sp, letterSpacing = 2.sp)
                        Text(movie.title, color = CinemaColors.Foreground, fontSize = if (wide) 34.sp else 25.sp,
                            fontWeight = FontWeight.SemiBold)
                        val metadata = listOfNotNull(
                            movie.subtitle.takeIf { it.isNotBlank() },
                            movie.durationMs.takeIf { it > 0 }?.let { movieRuntime(it) },
                            movie.contentRating.takeIf { it.isNotBlank() },
                            movie.videoResolution.takeIf { it.isNotBlank() }?.let { "${it.uppercase()} video" },
                            movie.rating?.let { "Rating $it" },
                        ).joinToString("  ·  ")
                        if (metadata.isNotBlank()) Text(metadata, color = CinemaColors.Muted, fontSize = 16.sp)
                        if (movie.watched) Text("Watched", color = CinemaColors.Accent, fontSize = 15.sp)
                        if (loading) Text("Refreshing movie details…", color = CinemaColors.Muted, fontSize = 15.sp)
                        Text(movie.summary.ifBlank { if (loading) "Loading synopsis…" else "No synopsis supplied by Plex." },
                            color = CinemaColors.Foreground.copy(alpha = 0.9f), fontSize = 18.sp, lineHeight = 27.sp)
                    }
                }
                if (error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ErrorNote(error, Modifier.weight(1f))
                        CinemaButton(onClick = onRetry, enabled = enabled) { ControlText("Retry details") }
                    }
                }
                val resume = movie.viewOffsetMs > 0
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CinemaButton(onClick = { onPlay(false) }, modifier = Modifier.focusRequester(playFocus),
                            selected = true, enabled = enabled) {
                            ControlText(if (resume) "Resume · ${movieRuntime(movie.viewOffsetMs)}" else "Play original")
                        }
                        if (resume) CinemaButton(onClick = { onPlay(true) }, enabled = enabled) { ControlText("Start over") }
                        Spacer(Modifier.weight(1f))
                        CinemaButton(onClick = onDismiss, enabled = enabled) { ControlText("Close") }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CinemaButton(onClick = { onPlay(false) },
                            modifier = Modifier.fillMaxWidth().focusRequester(playFocus), selected = true, enabled = enabled) {
                            ControlText(if (resume) "Resume · ${movieRuntime(movie.viewOffsetMs)}" else "Play original")
                        }
                        if (resume) CinemaButton(onClick = { onPlay(true) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                            ControlText("Start over")
                        }
                        CinemaButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = enabled) { ControlText("Close") }
                    }
                }
                Text("Original quality · Direct playback", color = CinemaColors.Muted, fontSize = 13.sp)
            }
        }
        LaunchedEffect(movie.ratingKey, enabled) {
            if (enabled) {
                withFrameNanos { }
                playFocus.requestFocus()
            }
        }
    }
}

private fun movieRuntime(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

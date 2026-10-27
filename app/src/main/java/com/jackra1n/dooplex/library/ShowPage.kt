package com.jackra1n.dooplex.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.details.DetailPlayIcon
import com.jackra1n.dooplex.details.DetailTitle
import com.jackra1n.dooplex.details.MovieDetailsBackdrop
import com.jackra1n.dooplex.details.MovieDetailsScreen
import com.jackra1n.dooplex.details.WatchlistButton
import com.jackra1n.dooplex.details.episodeNumber
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.rememberEdgeScroll
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.EpisodeCard
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.TextTab
import com.jackra1n.dooplex.ui.pageFade
import com.jackra1n.dooplex.ui.theme.CinemaColors
import com.jackra1n.dooplex.watchlist.LocalWatchlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * A show: its art and summary with Play for the next episode, then season tabs and that season's
 * episodes in a row. Moving down to the seasons lifts the page so the episodes come into view; an
 * episode opens its own details page.
 */
@Composable
internal fun ShowPage(
    state: ShowPageState,
    posters: PosterRepository,
    enabled: Boolean,
    onBack: () -> Unit,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
) {
    val show = state.show
    val selected = state.details.movie
    val watchlist = LocalWatchlist.current?.takeIf { show.guid.startsWith("plex://") }
    val playFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    var lower by remember { mutableStateOf(false) }
    val seasons = state.seasonList
    val tabs = !state.flat && seasons.size > 1
    val episodes = state.episodes
    val target = state.next ?: episodes?.itemAt(0)
    val meta = listOf(
        show.subtitle,
        show.contentRating,
        if (state.flat || seasons.isEmpty()) "" else "${seasons.size} ${if (seasons.size == 1) "season" else "seasons"}",
        show.genres.take(3).joinToString(" · "),
    ).filter { it.isNotBlank() }.joinToString("  ·  ")

    // The row takes focus back while details still covers it, so focus never falls to the first tab.
    fun closeDetails() {
        episodeFocus.requestFocus()
        state.details.close()
    }

    BackHandler(enabled = enabled && selected == null, onBack = onBack)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        playFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().pageFade()) { MovieDetailsBackdrop(show.artPath, posters, enabled && selected == null) }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val viewport = constraints.maxHeight.toFloat()
            var height by remember { mutableIntStateOf(0) }
            val shift by animateFloatAsState(
                if (lower) (height - viewport).coerceAtLeast(0f) else 0f,
                spring(dampingRatio = 1f, stiffness = 170f), label = "show page",
            )
            val edge = 56.dp
            val cardWidth = (maxWidth - edge * 2 - 18.dp * 3) / 4
            val artworkWidth = artworkBucket(with(LocalDensity.current) { cardWidth.toPx() })
            Column(
                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { height = it.height }
                    .graphicsLayer { translationY = -shift }
                    .pageFade(),
            ) {
                Column(
                    Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxHeight * 0.8f).padding(start = edge, end = edge, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTitle(show, posters, enabled, compact = false, height = 104.dp)
                        if (meta.isNotBlank()) Text(meta, color = CinemaColors.Muted, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val summary = state.error ?: show.summary
                        if (summary.isNotBlank()) Text(summary,
                            color = if (state.error != null) CinemaColors.Error else CinemaColors.Foreground.copy(alpha = 0.92f),
                            fontSize = 16.sp, lineHeight = 24.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.padding(top = 8.dp).focusProperties { down = if (tabs) seasonFocus else episodeFocus },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CinemaButton(
                            onClick = { target?.let { onPlay(it, false) } }, enabled = enabled, primary = true,
                            modifier = Modifier.focusRequester(playFocus),
                        ) {
                            DetailPlayIcon(false)
                            Text(
                                target?.let { (if (it.viewOffsetMs > 0) "Resume " else "Play ") + episodeNumber(it) } ?: "Play",
                                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                        if (watchlist != null) WatchlistButton(show, watchlist, enabled)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 32.dp).onFocusChanged { if (selected == null) lower = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tabs) SeasonTabs(state, seasons, enabled, seasonFocus, playFocus, episodeFocus)
                    if (episodes != null) key(episodes) {
                        Episodes(state, episodes, posters, enabled, cardWidth, artworkWidth, episodeFocus,
                            up = if (tabs) seasonFocus else playFocus)
                    }
                }
            }
        }
    }

    if (selected != null) MovieDetailsScreen(
        movie = remember(selected, show) { selected.copy(logoPath = selected.logoPath ?: show.logoPath) },
        posters = posters,
        loading = state.details.loading,
        error = state.details.error,
        enabled = enabled,
        onDismiss = ::closeDetails,
        onRetry = { state.details.open(selected) },
        onPlay = { fromBeginning ->
            closeDetails()
            onPlay(selected, fromBeginning)
        },
    )
}

/** Moving along the tabs shows that season after a short pause, so passing over one loads nothing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonTabs(
    state: ShowPageState,
    seasons: List<PlexBrowseItem>,
    enabled: Boolean,
    focus: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
) {
    val row = rememberLazyListState()
    val current = remember { FocusRequester() }
    var focused by remember { mutableStateOf<PlexBrowseItem?>(null) }
    val selectedIndex = seasons.indexOfFirst { it.ratingKey == state.season?.ratingKey }
    LaunchedEffect(focused) {
        val season = focused ?: return@LaunchedEffect
        delay(SEASON_PAUSE_MS)
        state.selectSeason(season)
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) row.scrollToItem(selectedIndex)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(46.dp)) {
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).focusRestorer(current)
                .focusProperties { this.up = up; this.down = down },
        ) {
            itemsIndexed(seasons, key = { _, season -> season.ratingKey }) { index, season ->
                TextTab(
                    label = season.title,
                    selected = index == selectedIndex,
                    enabled = enabled,
                    modifier = Modifier.then(if (index == selectedIndex) Modifier.focusRequester(current) else Modifier)
                        .onFocusChanged { if (it.isFocused) focused = season },
                    onClick = { state.selectSeason(season) },
                )
            }
        }
    }
}

/** Opens on the next episode to watch, else the first unwatched one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Episodes(
    state: ShowPageState,
    episodes: ShowBrowseState,
    posters: PosterRepository,
    enabled: Boolean,
    cardWidth: androidx.compose.ui.unit.Dp,
    artworkWidth: Int,
    focus: FocusRequester,
    up: FocusRequester,
) {
    val row = rememberLazyListState()
    val start = remember { FocusRequester() }
    var startIndex by remember { mutableIntStateOf(0) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val count = if (episodes.total > 0) episodes.total else if (episodes.loading) 4 else 0
    LaunchedEffect(Unit) {
        snapshotFlow { episodes.total > 0 && !episodes.loading }.first { it }
        val total = episodes.total
        val loaded = (0 until total).mapNotNull { index -> episodes.itemAt(index)?.let { index to it } }
        val next = state.next
        startIndex = loaded.firstOrNull { it.second.ratingKey == next?.ratingKey }?.first
            ?: next?.takeIf { it.parentIndex == state.season?.index }?.let { (it.index - 1).coerceIn(0, total - 1) }
            ?: loaded.firstOrNull { !it.second.watched }?.first
            ?: 0
        row.scrollToItem(startIndex)
    }
    LaunchedEffect(Unit) {
        snapshotFlow {
            row.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() }?.let { it.first().index to it.last().index }
        }.filterNotNull().distinctUntilChanged().collect { (first, last) -> episodes.requestRange(first, last) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(56.dp)) {
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).focusRestorer(start).focusProperties { this.up = up },
        ) {
            items(count, key = { it }) { index ->
                val item = remember(index) { derivedStateOf { episodes.itemAt(index) } }.value
                EpisodeCard(
                    item = item,
                    posters = posters,
                    artworkWidth = artworkWidth,
                    loadArtwork = enabled,
                    enabled = enabled,
                    modifier = Modifier.width(cardWidth).then(if (index == startIndex) Modifier.focusRequester(start) else Modifier),
                    onFocus = { focusedIndex = index },
                    onClick = { if (item != null) state.details.open(item) else episodes.retry() },
                    placeholderLabel = "Episode ${index + 1}",
                    label = item?.let(::episodeNumber),
                )
            }
        }
    }
    EpisodeSummary { episodes.itemAt(focusedIndex)?.summary.orEmpty() }
}

@Composable
private fun EpisodeSummary(summary: () -> String) {
    Text(
        summary(),
        color = CinemaColors.Foreground.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 22.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 56.dp).fillMaxWidth(0.7f).height(44.dp),
    )
}

private const val SEASON_PAUSE_MS = 250L

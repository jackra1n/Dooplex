package com.jackra1n.dooplex.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.details.MovieDetailsScreen
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.CollectionPosterCard
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors
import com.jackra1n.dooplex.ui.pageFade

/** The watchlist as a poster grid under the top bar; titles this server lacks are only counted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WatchlistScreen(
    state: WatchlistState,
    posters: PosterRepository,
    serverName: String,
    navigation: @Composable () -> Unit,
    focus: CollectionFocusState,
    enabled: Boolean,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
    onOpen: (PlexBrowseItem) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val selected = state.details.movie
    LaunchedEffect(focus.contentEpoch, state.loading) {
        if (focus.contentType != "watchlist" || state.loading) return@LaunchedEffect
        withFrameNanos { }
        if (state.items.isNotEmpty()) first.requestFocus() else if (state.error != null) retry.requestFocus()
        focus.completeContent("watchlist")
    }
    LaunchedEffect(focus.headerEpoch) {
        if (focus.headerRoute != "watchlist") return@LaunchedEffect
        withFrameNanos { }
        if (state.items.isNotEmpty()) first.requestFocus()
        focus.completeHeader("watchlist")
    }
    BackHandler(enabled = enabled && selected == null, onBack = onBack)

    // The bar stays fixed above the grid, so moving up to it never leaves it pressed against the edge.
    Column(Modifier.fillMaxSize().background(CinemaColors.Canvas)) {
        navigation()
        BoxWithConstraints(Modifier.fillMaxSize().pageFade()) {
            val edge = 56.dp
            val columns = 7
            val cardWidth = (maxWidth - edge * 2 - 18.dp * (columns - 1)) / columns
            val artworkWidth = artworkBucket(with(LocalDensity.current) { cardWidth.toPx() })
            val minimal = remember { object : BringIntoViewSpec {} }
            CompositionLocalProvider(LocalBringIntoViewSpec provides minimal) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = edge, end = edge, bottom = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Watchlist", color = CinemaColors.Foreground, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    state.loading -> "Loading your watchlist…"
                                    state.error != null -> state.error!!
                                    state.items.isEmpty() && state.missing == 0 ->
                                        "Nothing here yet. Add movies and shows to your watchlist in any Plex app."
                                    state.missing > 0 -> "${state.missing} more on your watchlist ${if (state.missing == 1) "isn't" else "aren't"} on $serverName."
                                    else -> "${state.items.size} ${if (state.items.size == 1) "title" else "titles"}"
                                },
                                color = if (state.error != null) CinemaColors.Error else CinemaColors.Muted,
                                fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                            )
                            if (state.error != null) CinemaButton(onClick = state::refresh, modifier = Modifier.focusRequester(retry)) {
                                Text("Try again", fontSize = 16.sp)
                            }
                        }
                    }
                    itemsIndexed(state.items, key = { _, item -> item.ratingKey }) { index, item ->
                        CollectionPosterCard(
                            item = item,
                            posters = posters,
                            artworkWidth = artworkWidth,
                            loadArtwork = enabled,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                            onFocus = {},
                            onClick = { if (item.type == "movie") state.details.open(item) else onOpen(item) },
                            placeholderLabel = item.title,
                        )
                    }
                }
            }
        }
    }

    if (selected != null) MovieDetailsScreen(
        movie = selected,
        posters = posters,
        loading = state.details.loading,
        error = state.details.error,
        enabled = enabled,
        onDismiss = state.details::close,
        onRetry = { state.details.open(selected) },
        onPlay = { fromBeginning ->
            state.details.close()
            onPlay(selected, fromBeginning)
        },
    )
}

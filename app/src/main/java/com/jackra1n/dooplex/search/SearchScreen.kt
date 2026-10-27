package com.jackra1n.dooplex.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.details.MovieDetailsScreen
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.CollectionPosterCard
import com.jackra1n.dooplex.ui.DooplexIcons
import com.jackra1n.dooplex.ui.Glass
import com.jackra1n.dooplex.ui.theme.CinemaColors

/**
 * Search across this server: the query large at the top, results as one shelf per kind under it.
 * Confirming the Search tab puts focus in the field and raises the on-screen keyboard.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SearchScreen(
    state: SearchState,
    posters: PosterRepository,
    serverName: String,
    navigation: @Composable () -> Unit,
    focus: CollectionFocusState,
    enabled: Boolean,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
    onOpen: (PlexBrowseItem) -> Unit,
    onBack: () -> Unit,
) {
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val selected = state.details.movie
    LaunchedEffect(focus.contentEpoch) {
        if (focus.contentType != "search") return@LaunchedEffect
        withFrameNanos { }
        field.requestFocus()
        keyboard?.show()
        focus.completeContent("search")
    }
    LaunchedEffect(focus.headerEpoch) {
        if (focus.headerRoute != "search") return@LaunchedEffect
        withFrameNanos { }
        field.requestFocus()
        focus.completeHeader("search")
    }
    BackHandler(enabled = enabled && selected == null, onBack = onBack)

    fun open(item: PlexBrowseItem) = when (item.type) {
        "movie" -> state.details.open(item)
        "episode" -> onPlay(item, false)
        else -> onOpen(item)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CinemaColors.Canvas)) {
        val edge = 56.dp
        val columns = 7
        val cardWidth = (maxWidth - edge * 2 - 18.dp * (columns - 1)) / columns
        val artworkWidth = artworkBucket(with(LocalDensity.current) { cardWidth.toPx() })
        val minimal = remember { object : BringIntoViewSpec {} }
        CompositionLocalProvider(LocalBringIntoViewSpec provides minimal) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 64.dp)) {
                item(key = "bar") { navigation() }
                item(key = "field") {
                    QueryField(state, field, serverName, enabled, Modifier.padding(horizontal = edge - 16.dp)) {
                        keyboard?.hide()
                    }
                }
                items(state.rows, key = { it.key }) { row ->
                    Column(Modifier.padding(top = 24.dp)) {
                        Text("${row.title}  ·  ${row.items.size}", color = CinemaColors.Foreground, fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = edge, bottom = 12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = edge),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(row.items, key = { it.ratingKey }) { item ->
                                CollectionPosterCard(
                                    item = item,
                                    posters = posters,
                                    artworkWidth = artworkWidth,
                                    loadArtwork = enabled,
                                    enabled = enabled,
                                    modifier = Modifier.width(cardWidth),
                                    onFocus = {},
                                    onClick = { open(item) },
                                    placeholderLabel = item.title,
                                )
                            }
                        }
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueryField(
    state: SearchState,
    requester: FocusRequester,
    serverName: String,
    enabled: Boolean,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) Glass.Plate else Glass.ScrimTop, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DooplexIcons.Search, contentDescription = null, tint = CinemaColors.Muted, modifier = Modifier.size(40.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Search", color = CinemaColors.Muted, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = state.query,
                    onValueChange = state::type,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(color = CinemaColors.Foreground, fontSize = 52.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(CinemaColors.Foreground),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused },
                )
            }
        }
        Text(
            when {
                state.error != null -> state.error!!
                state.query.trim().length < SearchState.MIN_QUERY -> "Type at least two letters to search $serverName"
                state.searching -> "Searching $serverName…"
                state.rows.isEmpty() -> "Nothing on $serverName matches “${state.query.trim()}”"
                else -> "Results from $serverName"
            },
            color = if (state.error != null) CinemaColors.Error else CinemaColors.Muted,
            fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp),
        )
    }
}

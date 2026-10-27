package com.jackra1n.dooplex.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.details.MovieDetailsScreen
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.CollectionPosterCard
import com.jackra1n.dooplex.ui.Glass
import com.jackra1n.dooplex.ui.theme.CinemaColors

/**
 * Search across this server with Dooplex's own keyboard on the left and results on the right. The
 * system keyboard never opens: on a TV it covers the results and is slow with a remote. A real
 * keyboard (or a remote with letter keys) still types straight into the query.
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
    val firstKey = remember { FocusRequester() }
    val selected = state.details.movie
    LaunchedEffect(focus.contentEpoch) {
        if (focus.contentType != "search") return@LaunchedEffect
        withFrameNanos { }
        firstKey.requestFocus()
        focus.completeContent("search")
    }
    LaunchedEffect(focus.headerEpoch) {
        if (focus.headerRoute != "search") return@LaunchedEffect
        withFrameNanos { }
        firstKey.requestFocus()
        focus.completeHeader("search")
    }
    BackHandler(enabled = enabled && selected == null, onBack = onBack)

    fun open(item: PlexBrowseItem) = when (item.type) {
        "movie" -> state.details.open(item)
        "episode" -> onPlay(item, false)
        else -> onOpen(item)
    }

    Column(
        Modifier.fillMaxSize().background(CinemaColors.Canvas).onPreviewKeyEvent { event ->
            if (!enabled || selected != null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            val typed = native.unicodeChar.toChar()
            when {
                native.keyCode == AndroidKeyEvent.KEYCODE_DEL -> state.type(state.query.dropLast(1))
                typed.isLetterOrDigit() || typed == ' ' -> state.type(state.query + typed.lowercaseChar())
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        navigation()
        Row(Modifier.fillMaxSize().padding(start = 56.dp)) {
            Column(Modifier.width(KEYBOARD_WIDTH)) {
                QueryLine(state.query)
                Text(
                    when {
                        state.error != null -> state.error!!
                        state.query.trim().length < SearchState.MIN_QUERY -> "Search movies, shows and episodes on $serverName"
                        state.searching -> "Searching $serverName…"
                        state.rows.isEmpty() -> "Nothing on $serverName matches “${state.query.trim()}”"
                        else -> "${state.rows.sumOf { it.items.size }} results on $serverName"
                    },
                    color = if (state.error != null) CinemaColors.Error else CinemaColors.Muted,
                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                )
                SearchKeyboard(
                    enabled = enabled,
                    firstKey = firstKey,
                    onKey = { state.type(state.query + it) },
                    onDelete = { state.type(state.query.dropLast(1)) },
                    onClear = { state.type("") },
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(start = 32.dp)) {
                val columns = 5
                val cardWidth = (maxWidth - 56.dp - 18.dp * (columns - 1)) / columns
                val artworkWidth = artworkBucket(with(LocalDensity.current) { cardWidth.toPx() })
                val minimal = remember { object : BringIntoViewSpec {} }
                CompositionLocalProvider(LocalBringIntoViewSpec provides minimal) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                        items(state.rows, key = { it.key }) { row ->
                            Column(Modifier.padding(bottom = 20.dp)) {
                                Text("${row.title}  ·  ${row.items.size}", color = CinemaColors.Foreground, fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(end = 56.dp),
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
private fun QueryLine(query: String) {
    Column {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(query.ifEmpty { "Search" }, color = if (query.isEmpty()) CinemaColors.Muted else CinemaColors.Foreground,
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.padding(start = 3.dp).size(width = 2.dp, height = 32.dp).background(CinemaColors.Foreground))
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(Glass.Rim))
    }
}

/** Letters and digits in a grid the D-pad can cross in a few presses, with Space, Delete and Clear. */
@Composable
private fun SearchKeyboard(
    enabled: Boolean,
    firstKey: FocusRequester,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        KEYS.chunked(KEYS_PER_ROW).forEachIndexed { row, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                keys.forEachIndexed { column, key ->
                    Key(key, KEY_SIZE, enabled, if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier) {
                        onKey(key)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            val wide = KEY_SIZE * 2 + KEY_GAP
            Key("Space", wide, enabled) { onKey(" ") }
            Key("Delete", wide, enabled, onClick = onDelete)
            Key("Clear", wide, enabled, onClick = onClear)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(label: String, width: Dp, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(width = width, height = KEY_SIZE).clip(RoundedCornerShape(12.dp))
            .background(if (focused) Glass.Light else KeyFill)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Glass.Ink else CinemaColors.Foreground,
            fontSize = if (label.length == 1) 21.sp else 15.sp, fontWeight = FontWeight.Medium)
    }
}

private val KEYS = ('a'..'z').map { it.toString() } + ('1'..'9').map { it.toString() } + "0"
private const val KEYS_PER_ROW = 6
private val KEY_SIZE = 50.dp
private val KEY_GAP = 6.dp
private val KEYBOARD_WIDTH = KEY_SIZE * KEYS_PER_ROW + KEY_GAP * (KEYS_PER_ROW - 1)
private val KeyFill = Color.White.copy(alpha = 0.06f)

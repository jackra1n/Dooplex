package com.jackra1n.dooplex.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jackra1n.dooplex.plex.PlexBrowseItem

/** Opens a title's menu; absent where titles have none. */
internal val LocalItemMenu = staticCompositionLocalOf<((PlexBrowseItem) -> Unit)?> { null }

/** OK activates a card; holding OK or pressing the remote's Menu key opens the title's menu. */
@Composable
internal fun Modifier.cardClick(item: PlexBrowseItem?, enabled: Boolean, onClick: () -> Unit): Modifier {
    val menu = LocalItemMenu.current
    val open: (() -> Unit)? = if (menu != null && item != null && enabled) { { menu(item) } } else null
    return onKeyEvent {
        if (open == null || it.key != Key.Menu) return@onKeyEvent false
        if (it.type == KeyEventType.KeyUp) open()
        true
    }.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
        onLongClick = open, onClick = { if (enabled) onClick() },
    )
}

@Composable
internal fun ItemMenu(
    item: PlexBrowseItem,
    watchlisted: Boolean?,
    onDismiss: () -> Unit,
    onMediaInfo: () -> Unit,
    onWatched: (Boolean) -> Unit,
    onWatchlist: () -> Unit,
) {
    val onServer = item.ratingKey.all(Char::isDigit)
    val actions = buildList {
        if (onServer && (item.type == "movie" || item.type == "episode")) add("Media info" to onMediaInfo)
        if (onServer) add((if (item.watched) "Mark as unwatched" else "Mark as watched") to { onWatched(!item.watched) })
        if (watchlisted != null) add((if (watchlisted) "Remove from watchlist" else "Add to watchlist") to onWatchlist)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.requestFocus()
    }
    GlassMenu(Alignment.Center, IntOffset.Zero, 420.dp, onDismiss) {
        MenuLabel(item.title)
        actions.forEachIndexed { index, (label, action) ->
            MenuRow(label, if (index == 0) Modifier.focusRequester(first) else Modifier, onClick = action)
        }
    }
}

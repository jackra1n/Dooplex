package com.jackra1n.dooplex.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.jackra1n.dooplex.plex.PlexBrowseItem
import kotlin.math.roundToInt

/** A title's menu, opened beside the card it came from; [continueWatching] adds the shelf's own removal. */
internal data class MenuRequest(val item: PlexBrowseItem, val anchor: Rect?, val continueWatching: Boolean = false)

/** Opens a title's menu; absent where titles have none. */
internal val LocalItemMenu = staticCompositionLocalOf<((MenuRequest) -> Unit)?> { null }

private class Placement {
    var coordinates: LayoutCoordinates? = null
}

/** OK activates a card; holding OK or pressing the remote's Menu key opens the title's menu. */
@Composable
internal fun Modifier.cardClick(item: PlexBrowseItem?, enabled: Boolean, onClick: () -> Unit): Modifier {
    val menu = LocalItemMenu.current
    val placement = remember { Placement() }
    val open: (() -> Unit)? = if (menu != null && item != null && enabled) {
        { menu(MenuRequest(item, placement.coordinates?.takeIf { it.isAttached }?.boundsInWindow())) }
    } else null
    return onPlaced { placement.coordinates = it }.onKeyEvent {
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
    request: MenuRequest,
    watchlisted: Boolean?,
    onDismiss: () -> Unit,
    onMediaInfo: () -> Unit,
    onWatched: (Boolean) -> Unit,
    onWatchlist: () -> Unit,
    onRemoveFromContinueWatching: () -> Unit,
) {
    val item = request.item
    val onServer = item.ratingKey.all(Char::isDigit)
    val actions = buildList {
        if (onServer && (item.type == "movie" || item.type == "episode")) add("Media info" to onMediaInfo)
        if (onServer) add((if (item.watched) "Mark as unwatched" else "Mark as watched") to { onWatched(!item.watched) })
        if (request.continueWatching && onServer) add("Remove from Continue Watching" to onRemoveFromContinueWatching)
        if (watchlisted != null) add((if (watchlisted) "Remove from watchlist" else "Add to watchlist") to onWatchlist)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.requestFocus()
    }
    val margin = with(LocalDensity.current) { 24.dp.toPx().roundToInt() }
    val position = remember(request.anchor, margin) { BesideCard(request.anchor, margin) }
    GlassMenu(position, 420.dp, onDismiss) {
        MenuLabel(item.title)
        actions.forEachIndexed { index, (label, action) ->
            MenuRow(label, if (index == 0) Modifier.focusRequester(first) else Modifier, onClick = action)
        }
    }
}

/** Right of the card, else left of it, top-aligned and kept on screen; centred when the card is unknown. */
private class BesideCard(private val anchor: Rect?, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        if (anchor == null) return IntOffset(maxX / 2 + margin / 2, maxY / 2 + margin / 2)
        val right = anchor.right.roundToInt() + margin
        val x = if (right <= maxX) right else anchor.left.roundToInt() - margin - popupContentSize.width
        return IntOffset(x.coerceIn(margin.coerceAtMost(maxX), maxX), anchor.top.roundToInt().coerceIn(margin.coerceAtMost(maxY), maxY))
    }
}

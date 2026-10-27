package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
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
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx().roundToInt() }
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val position = remember(request.anchor, margin) { BesideCard(request.anchor, margin) }
    val anchor = request.anchor
    val origin = when {
        anchor == null -> TransformOrigin.Center
        anchor.right + margin * 2 + with(density) { MENU_WIDTH.toPx() } <= windowWidth -> TransformOrigin(0f, 0f)
        else -> TransformOrigin(1f, 0f)
    }
    GlassMenu(position, MENU_WIDTH, origin, onDismiss) {
        MenuLabel(item.title)
        actions.forEachIndexed { index, (label, action) ->
            MenuRow(label, if (index == 0) Modifier.focusRequester(first) else Modifier, onClick = action)
        }
    }
}

/** Dims the page around the card a menu was opened from; it fades in with the menu and out after it. */
@Composable
internal fun MenuScrim(anchor: Rect?, visible: Boolean) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 180 else 140), label = "menu scrim")
    if (shown <= 0f) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onPlaced { origin = it.positionInWindow() }.drawWithCache {
        val hole = anchor?.translate(-origin)?.inflate(HOLE_MARGIN.toPx())
        val path = Path().apply {
            if (hole != null) addRoundRect(RoundRect(hole, CornerRadius(HOLE_CORNER.toPx())))
        }
        onDrawBehind {
            clipPath(path, ClipOp.Difference) { drawRect(Color.Black, alpha = SCRIM_ALPHA * shown) }
        }
    })
}

private val MENU_WIDTH = 420.dp
private val HOLE_MARGIN = 14.dp
private val HOLE_CORNER = 18.dp
private const val SCRIM_ALPHA = 0.55f

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

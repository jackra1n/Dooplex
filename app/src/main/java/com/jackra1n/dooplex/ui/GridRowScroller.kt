package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val RowSpring = spring(dampingRatio = 1f, stiffness = 170f, visibilityThreshold = 0.5f)

/**
 * Grid motion: a focused row rests where the first row does, reached on one critically damped
 * spring. Retargeting keeps the current speed, so a held D-pad glides instead of stepping.
 * The grid's lazy item 0 is its header; cards start at lazy index 1.
 */
internal class GridRowScroller(private val grid: LazyGridState, private val scope: CoroutineScope) {
    var columns = 1
    var spacing = 0f
    private var motion: Job? = null
    private var velocity = 0f
    private var headerHeight = 0

    fun showCard(index: Int) {
        val layout = grid.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { headerHeight = it.size.height }
        val card = layout.visibleItemsInfo.firstOrNull { it.index > 0 }
        if (card == null) {
            jump { grid.animateScrollToItem(1 + index / columns * columns) }
            return
        }
        val pitch = card.size.height + spacing
        val top = card.offset.y + (index / columns - (card.index - 1) / columns) * pitch
        // A tall header must not push the resting row below the screen.
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val rest = minOf(headerHeight + spacing, viewport - card.size.height - spacing).coerceAtLeast(0f)
        glide(top - rest)
    }

    fun showHeader() {
        val header = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
        if (header != null) glide(header.offset.y.toFloat()) else jump { grid.animateScrollToItem(0) }
    }

    private fun jump(block: suspend () -> Unit) {
        motion?.cancel()
        motion = scope.launch { block() }
    }

    private fun glide(distance: Float) {
        val start = if (motion?.isActive == true) velocity else 0f
        motion?.cancel()
        if (abs(distance) < 0.5f && start == 0f) return
        motion = scope.launch {
            grid.scroll {
                var moved = 0f
                AnimationState(0f, start).animateTo(distance, RowSpring) {
                    val wanted = value - moved
                    val consumed = scrollBy(wanted)
                    moved += consumed
                    this@GridRowScroller.velocity = velocity
                    // The list's end stops the spring instead of letting it push against the edge.
                    if (abs(consumed - wanted) > 0.5f) cancelAnimation()
                }
            }
            velocity = 0f
        }
    }
}

@Composable
internal fun rememberGridRowScroller(grid: LazyGridState): GridRowScroller {
    val scope = rememberCoroutineScope()
    return remember(grid) { GridRowScroller(grid, scope) }
}

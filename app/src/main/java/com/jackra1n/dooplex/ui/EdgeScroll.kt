package com.jackra1n.dooplex.ui

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Compose pivots TV focus to 30% of a row; this row only scrolls when a card would cross its
 * margins, so the first card returns exactly to its resting place.
 */
@Composable
fun rememberEdgeScroll(edge: Dp): BringIntoViewSpec {
    val edgePx = with(LocalDensity.current) { edge.toPx() }
    return remember(edgePx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < edgePx -> offset - edgePx
                offset + size > containerSize - edgePx -> offset + size - (containerSize - edgePx)
                else -> 0f
            }
        }
    }
}

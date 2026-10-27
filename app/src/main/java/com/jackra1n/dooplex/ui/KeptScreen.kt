package com.jackra1n.dooplex.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager

/** False inside a kept screen while it is hidden, so shared focus targets attach only where they are seen. */
internal val LocalScreenVisible = compositionLocalOf { true }

/** Hands [content] its screen's visibility; only this scope recomposes when it changes. */
@Composable
internal fun WhenVisible(content: @Composable (visible: Boolean) -> Unit) = content(LocalScreenVisible.current)

/**
 * Keeps a visited screen composed while another one shows. A hidden screen stays placed on its own
 * layer far above the window: the GPU skips it, its recorded drawing survives, and showing it again
 * only moves that layer instead of rebuilding and redrawing every card. Focus can't enter it while
 * hidden, and screens check [WhenVisible] before requesting focus. While [warm], a hidden screen is
 * drawn in place under an opaque cover, so its first real showing finds the GPU's caches ready.
 */
@Composable
internal fun KeptScreen(visible: Boolean, warm: Boolean = false, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val shown by rememberUpdatedState(visible)
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible && hasFocus) focusManager.clearFocus(force = true)
    }
    Layout(
        content = { CompositionLocalProvider(LocalScreenVisible provides visible, content = content) },
        modifier = Modifier.fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusProperties { onEnter = { if (!shown) cancelFocusChange() } }
            .focusGroup(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = if (visible || warm) 0 else -HIDDEN_OFFSET * constraints.maxHeight
            placeables.forEach { it.placeWithLayer(0, y) }
        }
    }
}

private const val HIDDEN_OFFSET = 4

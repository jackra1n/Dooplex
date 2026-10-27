package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A page change dips the page out, applies the change while it is hidden, and fades the new page
 * in once its first frame is drawn, so a slow build never shows. The top bar never fades.
 */
@Stable
internal class PageDip(private val scope: CoroutineScope) {
    private val fade = Animatable(1f)
    private var pending: (() -> Unit)? = null
    private var leaving = false
    private var job: Job? = null

    val alpha: Float get() = fade.value

    /** A second change while the page is still leaving replaces the first. */
    fun change(apply: () -> Unit) {
        pending = apply
        if (leaving) return
        job?.cancel()
        leaving = true
        job = scope.launch(AndroidUiDispatcher.Main) {
            fade.animateTo(0f, tween((OUT_MS * fade.value).roundToInt(), easing = Smooth))
            leaving = false
            pending?.also { pending = null }?.invoke()
            withFrameNanos { }
            withFrameNanos { }
            fade.animateTo(1f, tween(IN_MS, easing = Smooth))
        }
    }

    private companion object {
        // Leave fast so the press feels answered, arrive gently.
        const val OUT_MS = 70
        const val IN_MS = 140
        val Smooth = Easing { t -> t * t * (3 - 2 * t) }
    }
}

internal val LocalPageDip = staticCompositionLocalOf<PageDip> { error("No PageDip provided") }

/** Fades with page changes. Everything on a page except the top bar wears this. */
@Composable
internal fun Modifier.pageFade(): Modifier {
    val dip = LocalPageDip.current
    return graphicsLayer {
        alpha = dip.alpha
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

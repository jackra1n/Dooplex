package com.jackra1n.dooplex.ui

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The focused card's lit edge: a bright rim lit from above, a soft inner glow and a top-left gloss. */
internal fun Modifier.focusLight(light: State<Float>, radius: Dp): Modifier = drawWithCache {
    val rimWidth = 2.dp.toPx()
    val glowWidth = 10.dp.toPx()
    val rim = Brush.verticalGradient(0f to Color.White, 0.5f to Color.White.copy(alpha = 0.78f), 1f to Color.White.copy(alpha = 0.6f))
    val gloss = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.14f), 0.34f to Color.Transparent,
        start = Offset.Zero, end = Offset(size.width * 0.34f, size.height * 0.94f),
    )
    fun inset(width: Float) = Offset(width / 2, width / 2)
    fun inner(width: Float) = Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f))
    fun corner(width: Float) = CornerRadius((radius.toPx() - width / 2).coerceAtLeast(0f))
    onDrawWithContent {
        drawContent()
        val alpha = light.value
        if (alpha <= 0f) return@onDrawWithContent
        drawRect(gloss, alpha = alpha)
        drawRoundRect(Color.White, inset(glowWidth), inner(glowWidth), corner(glowWidth), alpha = alpha * 0.08f, style = Stroke(glowWidth))
        drawRoundRect(rim, inset(rimWidth), inner(rimWidth), corner(rimWidth), alpha = alpha, style = Stroke(rimWidth))
    }
}

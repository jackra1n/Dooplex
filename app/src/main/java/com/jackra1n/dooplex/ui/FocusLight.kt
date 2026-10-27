package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jackra1n.dooplex.artwork.MovieAtmosphere

/** How lit a focused card is; [glow] is the poster's colour for the halo around it. */
internal class FocusLight(val light: State<Float>) {
    var glow by mutableStateOf(Color.White)
}

@Composable
internal fun rememberFocusLight(focused: Boolean): FocusLight {
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "focus light")
    return remember { FocusLight(light) }
}

/** The poster's most vivid colour, lifted so it glows on the dark page; white when it is unknown. */
internal fun glowColor(atmosphere: MovieAtmosphere?): Color {
    val vivid = listOfNotNull(atmosphere?.left, atmosphere?.right, atmosphere?.lower)
        .filter { it.alpha > 0f }
        .maxByOrNull { val hsv = it.hsv(); hsv[1] * hsv[2] } ?: return Color.White
    val hsv = vivid.hsv()
    return Color.hsv(hsv[0], hsv[1].coerceIn(0.35f, 0.85f), 1f)
}

private fun Color.hsv() = FloatArray(3).also { android.graphics.Color.colorToHSV(toArgb(), it) }

/** A soft halo in the poster's colour around the card, drawn outside its bounds. */
internal fun Modifier.focusGlow(focus: FocusLight, radius: Dp): Modifier = drawBehind {
    val alpha = focus.light.value
    if (alpha <= 0f) return@drawBehind
    val ring = GLOW_SPREAD.toPx() / GLOW_RINGS
    val corner = radius.toPx()
    for (i in 0 until GLOW_RINGS) {
        val spread = ring * (i + 0.5f)
        val falloff = 1f - i / GLOW_RINGS.toFloat()
        drawRoundRect(
            focus.glow, Offset(-spread, -spread), Size(size.width + spread * 2, size.height + spread * 2),
            CornerRadius(corner + spread), alpha = alpha * 0.34f * falloff * falloff, style = Stroke(ring),
        )
    }
}

/** Over the artwork, as plx-native lights a focused tile: a soft top highlight and a rim lit from the top-left. */
internal fun Modifier.focusSheen(focus: FocusLight, radius: Dp): Modifier = drawWithCache {
    val highlight = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
        center = Offset(size.width * 0.5f, 0f), radius = size.height.coerceAtLeast(1f),
    )
    val shine = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.72f),
        0.22f to Color.White.copy(alpha = 0.28f),
        0.58f to Color.White.copy(alpha = 0.07f),
        1f to Color.White.copy(alpha = 0.20f),
        start = Offset.Zero, end = Offset(size.width, size.height),
    )
    val edge = Stroke(1.dp.toPx())
    val halo = Stroke(3.dp.toPx())
    val inset = edge.width / 2
    val outline = Size((size.width - edge.width).coerceAtLeast(0f), (size.height - edge.width).coerceAtLeast(0f))
    val corner = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f))
    onDrawWithContent {
        drawContent()
        val alpha = focus.light.value
        if (alpha <= 0f) return@onDrawWithContent
        drawRect(highlight, alpha = alpha)
        drawRoundRect(shine, Offset(inset, inset), outline, corner, alpha = alpha * 0.14f, style = halo)
        drawRoundRect(shine, Offset(inset, inset), outline, corner, alpha = alpha, style = edge)
    }
}

private val GLOW_SPREAD = 18.dp
private const val GLOW_RINGS = 6

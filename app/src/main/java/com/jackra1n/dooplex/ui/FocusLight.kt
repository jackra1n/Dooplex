package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** How lit a focused card is: [light] fades its glow and rim, [sweep] runs one shine across it per focus. */
internal class FocusLight(val light: State<Float>, val sweep: State<Float>) {
    var glow by mutableStateOf(Color.White)
}

@Composable
internal fun rememberFocusLight(focused: Boolean): FocusLight {
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "focus light")
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(SWEEP_MS, delayMillis = 90, easing = FastOutSlowInEasing))
    }
    return remember { FocusLight(light, sweep.asState()) }
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

/** Over the artwork: a rim lit along the top, a top-left gloss, and the shine that crosses once on focus. */
internal fun Modifier.focusSheen(focus: FocusLight, radius: Dp): Modifier = drawWithCache {
    val rimWidth = 1.5.dp.toPx()
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.95f), 0.18f to Color.White.copy(alpha = 0.35f), 1f to Color.White.copy(alpha = 0.18f),
    )
    val gloss = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.16f), 0.36f to Color.Transparent,
        start = Offset.Zero, end = Offset(size.width * 0.36f, size.height * 0.94f),
    )
    val diagonal = size.width + size.height
    val band = diagonal * 0.22f
    val corner = CornerRadius((radius.toPx() - rimWidth / 2).coerceAtLeast(0f))
    val inner = Size(size.width - rimWidth, size.height - rimWidth)
    onDrawWithContent {
        drawContent()
        val alpha = focus.light.value
        if (alpha <= 0f) return@onDrawWithContent
        drawRect(gloss, alpha = alpha)
        val sweep = focus.sweep.value
        if (sweep < 1f) {
            val center = -band + (diagonal + band * 2) * sweep
            val shine = Brush.linearGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.26f), 1f to Color.Transparent,
                start = Offset(center - band, 0f), end = Offset(center, band),
            )
            drawRect(shine, alpha = alpha)
        }
        drawRoundRect(rim, Offset(rimWidth / 2, rimWidth / 2), inner, corner, alpha = alpha, style = Stroke(rimWidth))
    }
}

private val GLOW_SPREAD = 18.dp
private const val GLOW_RINGS = 6
private const val SWEEP_MS = 750

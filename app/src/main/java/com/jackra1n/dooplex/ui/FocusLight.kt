package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jackra1n.dooplex.artwork.MovieAtmosphere
import com.jackra1n.dooplex.ui.theme.CinemaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How lit and how grown a focused card is; [glow] is the poster's colour for the halo around it.
 * Everything here is read while placing or drawing, so a focus move recomposes no card, and a
 * card that is never focused starts no animation.
 */
internal class FocusLight(private val scope: CoroutineScope) {
    private val amount = Animatable(0f)
    private val growth = Animatable(0f)
    private var lit by mutableStateOf(false)
    var glow by mutableStateOf(Color.White)

    val light: Float get() = amount.value
    val grown: Float get() = growth.value

    var focused: Boolean
        get() = lit
        set(value) {
            if (value == lit) return
            lit = value
            val target = if (value) 1f else 0f
            scope.launch { amount.animateTo(target, tween(160)) }
            scope.launch { growth.animateTo(target, tween(180)) }
        }

    val titleColor = ColorProducer { if (lit) CinemaColors.Foreground else DimTitle }
}

private val DimTitle = CinemaColors.Foreground.copy(alpha = 0.7f)

@Composable
internal fun rememberFocusLight(): FocusLight {
    val scope = rememberCoroutineScope()
    return remember { FocusLight(scope) }
}

/** Draws a focused card over its neighbours. */
internal fun Modifier.focusRaise(focus: FocusLight): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = if (focus.focused) 1f else 0f) }
}

internal fun Modifier.focusScale(focus: FocusLight, scale: Float): Modifier = graphicsLayer {
    scaleX = 1f + (scale - 1f) * focus.grown
    scaleY = scaleX
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

/**
 * A soft halo in the poster's colour around the card, drawn outside its bounds like a CSS box shadow:
 * a gradient strip along each side and a radial gradient at each corner, all with the same falloff,
 * so the halo fades continuously without any blur.
 */
internal fun Modifier.focusGlow(focus: FocusLight, radius: Dp): Modifier = drawWithCache {
    val spread = GLOW_SPREAD.toPx()
    val r = radius.toPx()
    val reach = r + spread
    val w = size.width
    val h = size.height
    val color = focus.glow
    val stops = GLOW_FALLOFF.map { (t, a) -> t to color.copy(alpha = color.alpha * a) }.toTypedArray()
    val cornerStops = GLOW_FALLOFF.map { (t, a) -> (r + t * spread) / reach to color.copy(alpha = color.alpha * a) }.toTypedArray()
    fun corner(x: Float, y: Float) = Brush.radialGradient(*cornerStops, center = Offset(x, y), radius = reach)
    val corners = listOf(
        Offset(r, r) to Offset(-spread, -spread),
        Offset(w - r, r) to Offset(w - r, -spread),
        Offset(r, h - r) to Offset(-spread, h - r),
        Offset(w - r, h - r) to Offset(w - r, h - r),
    ).map { (center, topLeft) -> corner(center.x, center.y) to topLeft }
    val cornerSize = Size(reach, reach)
    val top = Brush.verticalGradient(*stops, startY = 0f, endY = -spread)
    val bottom = Brush.verticalGradient(*stops, startY = h, endY = h + spread)
    val left = Brush.horizontalGradient(*stops, startX = 0f, endX = -spread)
    val right = Brush.horizontalGradient(*stops, startX = w, endX = w + spread)
    onDrawBehind {
        val alpha = focus.light
        if (alpha <= 0f) return@onDrawBehind
        corners.forEach { (brush, topLeft) -> drawRect(brush, topLeft, cornerSize, alpha = alpha) }
        drawRect(top, Offset(r, -spread), Size(w - r * 2, spread), alpha = alpha)
        drawRect(bottom, Offset(r, h), Size(w - r * 2, spread), alpha = alpha)
        drawRect(left, Offset(-spread, r), Size(spread, h - r * 2), alpha = alpha)
        drawRect(right, Offset(w, r), Size(spread, h - r * 2), alpha = alpha)
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
        val alpha = focus.light
        if (alpha <= 0f) return@onDrawWithContent
        drawRect(highlight, alpha = alpha)
        drawRoundRect(shine, Offset(inset, inset), outline, corner, alpha = alpha * 0.14f, style = halo)
        drawRoundRect(shine, Offset(inset, inset), outline, corner, alpha = alpha, style = edge)
    }
}

private val GLOW_SPREAD = 22.dp

/** Distance out from the card (0 at its edge) to opacity: an eased fall to nothing, close to a blur's. */
private val GLOW_FALLOFF = listOf(0f to 0.36f, 0.15f to 0.26f, 0.35f to 0.14f, 0.6f to 0.05f, 0.82f to 0.012f, 1f to 0f)

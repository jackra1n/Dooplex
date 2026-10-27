package com.jackra1n.dooplex.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Glass tokens: a thin dark scrim with a hairline rim lit from above. */
internal object Glass {
    val ScrimTop = Color.Black.copy(alpha = 0.20f)
    val ScrimBottom = Color.Black.copy(alpha = 0.36f)
    val Rim = Color.White.copy(alpha = 0.14f)
    val RimLight = Color.White.copy(alpha = 0.28f)
    val ControlRim = Color.White.copy(alpha = 0.22f)
    val ControlTop = Color(0xFF2C2C30).copy(alpha = 0.78f)
    val ControlBottom = Color(0xFF1C1C1F).copy(alpha = 0.84f)
    val Plate = Color.White.copy(alpha = 0.14f)
    val Light = Color(0xFFF7FAFC)
    val LightTop = Color(0xFFF6F6F6)
    val LightBottom = Color(0xFFE2E2E2)
    val Ink = Color(0xFF08080A)
    val IdleInk = Color(0xFFCDD3DD)
}

/** The rim is brightest on the top edge and fades around the corners to the perimeter weight. */
internal fun litRim(top: Float, height: Float, radius: Float, rim: Color = Glass.Rim, light: Color = Glass.RimLight): Brush {
    val fade = (radius / height).coerceIn(0.001f, 0.5f)
    return Brush.verticalGradient(0f to light, fade to rim, 1f to rim, startY = top, endY = top + height)
}

internal fun DrawScope.drawRim(brush: Brush, topLeft: Offset, size: Size, radius: Float, alpha: Float = 1f) {
    val width = 1f
    val inset = width / 2
    drawRoundRect(
        brush, topLeft + Offset(inset, inset), Size(size.width - width, size.height - width),
        CornerRadius((radius - inset).coerceAtLeast(0f)), alpha = alpha, style = Stroke(width),
    )
}

internal fun Modifier.glassRim(radius: Dp): Modifier = drawWithCache {
    val corner = radius.toPx()
    val brush = litRim(0f, size.height, corner, Glass.ControlRim)
    onDrawWithContent {
        drawContent()
        drawRim(brush, Offset.Zero, size, corner)
    }
}

internal fun Modifier.glassTrack(): Modifier = drawWithCache {
    val radius = size.height / 2
    val scrim = Brush.verticalGradient(listOf(Glass.ScrimTop, Glass.ScrimBottom))
    val rim = litRim(0f, size.height, radius)
    onDrawBehind {
        drawRoundRect(scrim, cornerRadius = CornerRadius(radius))
        drawRim(rim, Offset.Zero, size, radius)
    }
}

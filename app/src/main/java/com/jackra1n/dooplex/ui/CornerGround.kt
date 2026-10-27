package com.jackra1n.dooplex.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** One colour per screen corner, blended across the screen: Plex's UltraBlur shape. */
@Immutable
internal data class CornerColors(val topLeft: Color, val topRight: Color, val bottomRight: Color, val bottomLeft: Color) {
    companion object {
        /** The ground before any artwork exists: warm light at the top left, dark at the top right. */
        val Welcome = CornerColors(Color(0xFF4A4640), Color(0xFF18181A), Color(0xFF5E3E24), Color(0xFF42424A))
    }
}

/** Blended into a tiny bitmap once and stretched, so each frame costs the GPU a single texture fill. */
@Composable
internal fun CornerGround(colors: CornerColors, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val image = cornerBitmap(colors)
        val screen = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind { drawImage(image, dstSize = screen, filterQuality = FilterQuality.Low) }
    })
}

private fun cornerBitmap(colors: CornerColors): ImageBitmap {
    val width = 32
    val height = 18
    val pixels = IntArray(width * height) { index ->
        val x = (index % width + 0.5f) / width
        val y = (index / width + 0.5f) / height
        lerp(lerp(colors.topLeft, colors.topRight, x), lerp(colors.bottomLeft, colors.bottomRight, x), y).toArgb()
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

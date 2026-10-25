package com.jackra1n.dooplex

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.lerp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Immutable
internal data class MovieAtmosphere(val left: Color, val right: Color, val lower: Color) {
    fun blend(other: MovieAtmosphere, fraction: Float) = MovieAtmosphere(
        lerp(left, other.left, fraction), lerp(right, other.right, fraction), lerp(lower, other.lower, fraction),
    )

    companion object {
        val Neutral = MovieAtmosphere(Color.Transparent, Color.Transparent, Color.Transparent)
    }
}

/** At most two static gradient layers; only their compositor opacity changes during the fade. */
@Composable
internal fun MovieBackdrop(
    movie: PlexBrowseItem?,
    posters: PosterRepository,
    artworkWidth: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val palettes = remember(posters) { LinkedHashMap<String, MovieAtmosphere>(64, 0.75f, true) }
    var resolved by remember(posters) { mutableStateOf(MovieAtmosphere.Neutral) }
    var previous by remember(posters) { mutableStateOf(MovieAtmosphere.Neutral) }
    var current by remember(posters) { mutableStateOf(MovieAtmosphere.Neutral) }
    val fade = remember(posters) { Animatable(1f) }
    val path = movie?.thumbPath

    LaunchedEffect(posters, path, artworkWidth, active) {
        if (!active) return@LaunchedEffect
        // Rapid focus traversal retains the current wash: no extra requests or pixel work.
        delay(180)
        if (path == null) {
            resolved = MovieAtmosphere.Neutral
            return@LaunchedEffect
        }
        palettes[path]?.let {
            resolved = it
            return@LaunchedEffect
        }
        try {
            val height = artworkWidth * 3 / 2
            val bitmap = posters.cached(path, artworkWidth, height) ?: posters.load(path, artworkWidth, height)
            val colors = extractMovieAtmosphere(bitmap)
            currentCoroutineContext().ensureActive()
            palettes[path] = colors
            if (palettes.size > 64) palettes.entries.iterator().apply { next(); remove() }
            resolved = colors
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            resolved = MovieAtmosphere.Neutral
        }
    }
    LaunchedEffect(resolved, posters) {
        if (resolved == current) return@LaunchedEffect
        previous = previous.blend(current, fade.value)
        current = resolved
        fade.snapTo(0f)
        fade.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    Box(modifier) {
        AtmosphericWash(previous, Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
            alpha = 1f - fade.value
        })
        AtmosphericWash(current, Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
            alpha = fade.value
        })
    }
}

@Composable
private fun AtmosphericWash(colors: MovieAtmosphere, modifier: Modifier) {
    if (colors == MovieAtmosphere.Neutral) return
    Box(modifier.drawWithCache {
        val radius = maxOf(size.width, size.height) * 0.72f
        val left = Brush.radialGradient(
            listOf(colors.left.copy(alpha = colors.left.alpha * 0.58f), Color.Transparent),
            Offset(size.width * 0.08f, size.height * 0.20f), radius,
        )
        val right = Brush.radialGradient(
            listOf(colors.right.copy(alpha = colors.right.alpha * 0.50f), Color.Transparent),
            Offset(size.width * 0.93f, size.height * 0.28f), radius,
        )
        val lower = Brush.radialGradient(
            listOf(colors.lower.copy(alpha = colors.lower.alpha * 0.24f), Color.Transparent),
            Offset(size.width * 0.56f, size.height * 1.08f), radius * 0.80f,
        )
        onDrawBehind {
            drawRect(left)
            drawRect(right)
            drawRect(lower)
        }
    })
}

/** Samples a fixed 18×27 grid off-main, not a full-resolution image or a per-frame blur. */
internal suspend fun extractMovieAtmosphere(bitmap: Bitmap): MovieAtmosphere = withContext(Dispatchers.Default) {
    val sums = FloatArray(12)
    val context = currentCoroutineContext()
    fun accumulate(band: Int, red: Float, green: Float, blue: Float, weight: Float) {
        val offset = band * 4
        sums[offset] += red * weight
        sums[offset + 1] += green * weight
        sums[offset + 2] += blue * weight
        sums[offset + 3] += weight
    }
    for (row in 0 until 27) {
        context.ensureActive()
        val y = ((row + 0.5f) * bitmap.height / 27).toInt().coerceAtMost(bitmap.height - 1)
        for (column in 0 until 18) {
            val x = ((column + 0.5f) * bitmap.width / 18).toInt().coerceAtMost(bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) / 255f
            val red = ((pixel ushr 16) and 255) / 255f
            val green = ((pixel ushr 8) and 255) / 255f
            val blue = (pixel and 255) / 255f
            val maximum = maxOf(red, green, blue)
            if (alpha < 0.5f || maximum < 0.045f) continue
            val saturation = (maximum - minOf(red, green, blue)) / maximum
            val weight = alpha * (0.18f + saturation * 0.82f)
            accumulate(if (column < 9) 0 else 1, red, green, blue, weight)
            if (row >= 14) accumulate(2, red, green, blue, weight)
        }
    }
    fun color(band: Int): Color {
        val offset = band * 4
        val weight = sums[offset + 3]
        if (weight <= 0f) return Color.Transparent
        val red = sums[offset] / weight
        val green = sums[offset + 1] / weight
        val blue = sums[offset + 2] / weight
        val gray = (red + green + blue) / 3
        val scale = minOf(1.35f, 0.52f / maxOf(red, green, blue))
        return Color(
            ((red * 0.88f + gray * 0.12f) * scale).coerceIn(0f, 0.55f),
            ((green * 0.88f + gray * 0.12f) * scale).coerceIn(0f, 0.55f),
            ((blue * 0.88f + gray * 0.12f) * scale).coerceIn(0f, 0.55f),
        )
    }
    MovieAtmosphere(color(0), color(1), color(2))
}

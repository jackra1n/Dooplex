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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Immutable
internal data class MovieAtmosphere(val left: Color, val right: Color, val lower: Color) {
    companion object {
        val Neutral = MovieAtmosphere(Color.Transparent, Color.Transparent, Color.Transparent)
    }
}

private fun MovieAtmosphere.blend(other: MovieAtmosphere, fraction: Float) = MovieAtmosphere(
    lerp(left, other.left, fraction), lerp(right, other.right, fraction), lerp(lower, other.lower, fraction),
)

/** Cached palettes start blending immediately; cold artwork retains the last wash until ready. */
@Composable
internal fun MovieBackdrop(
    movie: PlexBrowseItem?,
    posters: PosterRepository,
    artworkWidth: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val path = movie?.thumbPath
    val artworkHeight = artworkWidth * 3 / 2
    var loaded by remember(posters, path, artworkWidth) { mutableStateOf<MovieAtmosphere?>(null) }
    val cached = path?.let { posters.cachedAtmosphere(it, artworkWidth, artworkHeight) }
    LaunchedEffect(posters, path, artworkWidth, active) {
        if (!active || path == null || cached != null) return@LaunchedEffect
        try {
            posters.load(path, artworkWidth, artworkHeight)
            currentCoroutineContext().ensureActive()
            loaded = posters.cachedAtmosphere(path, artworkWidth, artworkHeight) ?: MovieAtmosphere.Neutral
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            loaded = MovieAtmosphere.Neutral
        }
    }
    val target = if (path == null) MovieAtmosphere.Neutral else cached ?: loaded
    var previous by remember(posters) { mutableStateOf(MovieAtmosphere.Neutral) }
    var current by remember(posters) { mutableStateOf(cached ?: MovieAtmosphere.Neutral) }
    val fade = remember(posters) { Animatable(1f) }
    LaunchedEffect(posters, target) {
        if (target == null || target == current) return@LaunchedEffect
        previous = previous.blend(current, fade.value)
        current = target
        fade.snapTo(0f)
        fade.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
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

/** At most two cached brush layers; the short transition only changes compositor opacity. */
@Composable
private fun AtmosphericWash(colors: MovieAtmosphere, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val radius = maxOf(size.width, size.height) * 0.62f
        val left = Brush.radialGradient(
            listOf(colors.left.copy(alpha = colors.left.alpha * 0.28f), Color.Transparent),
            Offset(size.width * 0.03f, size.height * 0.08f), radius,
        )
        val right = Brush.radialGradient(
            listOf(colors.right.copy(alpha = colors.right.alpha * 0.26f), Color.Transparent),
            Offset(size.width * 0.98f, size.height * 0.16f), radius,
        )
        val lower = Brush.radialGradient(
            listOf(colors.lower.copy(alpha = colors.lower.alpha * 0.23f), Color.Transparent),
            Offset(size.width * 0.96f, size.height * 1.02f), radius * 0.90f,
        )
        // This corner intentionally stays almost neutral instead of combining poster hues.
        val muted = Brush.radialGradient(
            listOf(Color(0xFF29292D).copy(alpha = 0.88f), Color.Transparent),
            Offset(size.width * 0.05f, size.height * 0.94f), radius * 0.88f,
        )
        onDrawBehind {
            if (colors != MovieAtmosphere.Neutral) {
                drawRect(left)
                drawRect(right)
                drawRect(lower)
                drawRect(muted)
            }
        }
    })
}

/**
 * Samples only 18×27 pixels off-main once per repository bitmap. Hue buckets preserve
 * distinct saturated regions instead of averaging complementary colors into a gray wash.
 * Region-weighted selection favors the poster's layout, with separated hues where available.
 * Selected hues blend toward luminance gray so artwork stays the focus, not the background.
 */
internal suspend fun extractMovieAtmosphere(bitmap: Bitmap): MovieAtmosphere = withContext(Dispatchers.Default) {
    val hueBuckets = 12
    val neutralBucket = hueBuckets
    // Each bucket stores weighted RGB, total weight and three spatial-region weights.
    val stride = 7
    val sums = FloatArray((hueBuckets + 1) * stride)
    val context = currentCoroutineContext()
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
            val delta = maximum - minOf(red, green, blue)
            val saturation = delta / maximum
            val bucket = if (saturation < 0.18f) {
                neutralBucket
            } else {
                val hue = when (maximum) {
                    red -> (green - blue) / delta
                    green -> (blue - red) / delta + 2f
                    else -> (red - green) / delta + 4f
                }
                (((hue + 6f) % 6f) * 2f).toInt().coerceIn(0, hueBuckets - 1)
            }
            val weight = alpha * (0.18f + saturation * 0.82f) * (0.25f + maximum * 0.75f)
            val offset = bucket * stride
            sums[offset] += red * weight
            sums[offset + 1] += green * weight
            sums[offset + 2] += blue * weight
            sums[offset + 3] += weight
            if (row < 18) sums[offset + if (column < 9) 4 else 5] += weight
            if (row >= 14) sums[offset + 6] += weight
        }
    }
    var strongestHue = 0f
    var chromaticWeight = 0f
    for (bucket in 0 until hueBuckets) {
        val weight = sums[bucket * stride + 3]
        strongestHue = maxOf(strongestHue, weight)
        chromaticWeight += weight
    }
    // Tiny colored details must not turn an otherwise monochrome image into a neon wash.
    val useHues = chromaticWeight > sums[neutralBucket * stride + 3] * 0.15f
    val selected = IntArray(3) { -1 }
    fun dominant(region: Int, distinct: Boolean): Int {
        var best = -1
        var bestScore = 0f
        for (bucket in 0 until hueBuckets) {
            val offset = bucket * stride
            val weight = sums[offset + 3]
            if (weight < strongestHue * 0.10f) continue
            if (distinct && selected.any { previous ->
                    if (previous < 0) false else {
                        val distance = abs(bucket - previous)
                        minOf(distance, hueBuckets - distance) < 2
                    }
                }) continue
            val score = weight * 0.45f + sums[offset + 4 + region] * 0.90f
            if (score > bestScore) {
                bestScore = score
                best = bucket
            }
        }
        return best
    }
    fun color(bucket: Int): Color {
        if (bucket < 0) return Color.Transparent
        val offset = bucket * stride
        val weight = sums[offset + 3]
        if (weight <= 0f) return Color.Transparent
        val red = sums[offset] / weight
        val green = sums[offset + 1] / weight
        val blue = sums[offset + 2] / weight
        val ceiling = if (bucket == neutralBucket) 0.36f else 0.72f
        val scale = minOf(1.8f, ceiling / maxOf(red, green, blue))
        val gray = red * 0.2126f + green * 0.7152f + blue * 0.0722f
        return Color(
            ((gray + (red - gray) * 0.60f) * scale).coerceIn(0f, ceiling),
            ((gray + (green - gray) * 0.60f) * scale).coerceIn(0f, ceiling),
            ((gray + (blue - gray) * 0.60f) * scale).coerceIn(0f, ceiling),
        )
    }
    for (region in selected.indices) {
        selected[region] = if (useHues) {
            dominant(region, distinct = true).takeIf { it >= 0 } ?: dominant(region, distinct = false)
        } else {
            neutralBucket
        }
    }
    context.ensureActive()
    MovieAtmosphere(color(selected[0]), color(selected[1]), color(selected[2]))
}

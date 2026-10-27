package com.jackra1n.dooplex.artwork

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.ui.theme.CinemaColors
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal fun artworkBucket(pixels: Float): Int = when {
    pixels <= 192 -> 192
    pixels <= 256 -> 256
    pixels <= 384 -> 384
    else -> 512
}

/**
 * Cache hits are synchronous; inactive artwork retains its image but never starts cold requests.
 * Clear logos share the bounded ARGB cache and aspect-fit PNG transcode; callers use ContentScale.Fit
 * to keep transparent title artwork intact instead of applying the poster's crop.
 */
@Composable
internal fun rememberMovieArtworkPainter(
    path: String?,
    posters: PosterRepository,
    width: Int,
    height: Int,
    active: Boolean,
): Painter? {
    val cached = remember(posters, path, width, height) { path?.let { posters.cached(it, width, height) } }
    var bitmap by remember(posters, path, width, height) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(posters, path, width, height, active) {
        if (path == null) return@LaunchedEffect
        posters.cached(path, width, height)?.let { bitmap = it }
        if (!active || bitmap != null) return@LaunchedEffect
        try {
            val started = System.nanoTime()
            bitmap = posters.load(path, width, height)
            Analytics.sample("poster.wait.ms", (System.nanoTime() - started) / 1e6)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // Keep the existing image or fallback until a later visit can retry.
        }
    }
    return remember(bitmap) { bitmap?.let { BitmapPainter(it.asImageBitmap()) } }
}

@Composable
internal fun PosterArtwork(
    path: String?,
    posters: PosterRepository,
    width: Int,
    load: Boolean,
    modifier: Modifier,
    title: String,
    metadataLoading: Boolean = false,
) {
    val painter = rememberMovieArtworkPainter(path, posters, width, width * 3 / 2, load)
    val reveal = rememberArtworkReveal(path, width, painter != null)
    // The placeholder is only painted until the poster above it is fully opaque.
    Box(modifier.drawBehind { if (reveal.value < 1f) drawRect(PlaceholderBrush) }, contentAlignment = Alignment.Center) {
        if (painter != null) {
            ArtworkImage(painter, reveal, Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(title.take(1).uppercase(), color = CinemaColors.Accent.copy(alpha = 0.45f),
                    fontSize = 44.sp, fontWeight = FontWeight.Light)
                if (path == null && !metadataLoading) Text("NO ARTWORK", color = CinemaColors.Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, maxLines = 1)
            }
        }
    }
}

private val PlaceholderBrush = Brush.verticalGradient(listOf(CinemaColors.Panel, CinemaColors.Canvas))

/** Artwork that arrives after its card appeared fades in; cache hits start fully shown. */
@Composable
internal fun rememberArtworkReveal(path: String?, width: Int, ready: Boolean): Animatable<Float, AnimationVector1D> {
    val reveal = remember(path, width) { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(reveal, ready) { if (ready) reveal.animateTo(1f, tween(220)) }
    return reveal
}

@Composable
internal fun ArtworkImage(painter: Painter, reveal: Animatable<Float, AnimationVector1D>, modifier: Modifier) {
    Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            alpha = reveal.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        })
}

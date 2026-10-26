package com.jackra1n.dooplex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors
import kotlinx.coroutines.launch

/**
 * An opaque detail destination: Back and playback stay outside the scrolling reading viewport.
 * Initial focus is requested only for a new rating key or when re-enabled, never for metadata refresh.
 * Up from playback enters the reader; its D-pad Up/Down scroll real, untruncated text. At the top,
 * Up reaches Back; at the bottom, Down returns to Play. Focus geometry never depends on artwork.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MovieDetailsScreen(
    movie: PlexBrowseItem,
    posters: PosterRepository,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Boolean) -> Unit,
) {
    val playFocus = remember(movie.ratingKey) { FocusRequester() }
    val backFocus = remember(movie.ratingKey) { FocusRequester() }
    val readingFocus = remember(movie.ratingKey) { FocusRequester() }
    val scroll = remember(movie.ratingKey) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    var readingFocused by remember(movie.ratingKey) { mutableStateOf(false) }
    var lastFocus by remember(movie.ratingKey) { mutableStateOf(DetailFocus.Play) }
    val resume = movie.viewOffsetMs > 0 && (movie.durationMs <= 0 || movie.viewOffsetMs < movie.durationMs)
    val metadata = remember(movie) {
        listOfNotNull(
            movie.subtitle.takeIf { it.isNotBlank() },
            movie.durationMs.takeIf { it > 0 }?.let(::detailRuntime),
            movie.contentRating.takeIf { it.isNotBlank() },
            movie.videoResolution.takeIf { it.isNotBlank() }?.let { "${it.uppercase()} video" },
            movie.rating?.let { "Rating $it" },
            "Watched".takeIf { movie.watched },
        ).joinToString("  ·  ")
    }

    BackHandler(enabled = enabled, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize().background(CinemaColors.Canvas)) {
        val compact = maxHeight < 500.dp || maxWidth < 900.dp
        val edge = if (compact) 16.dp else 36.dp
        val gap = if (compact) 12.dp else 24.dp
        val posterWidth = if (compact) 112.dp else 256.dp
        val density = LocalDensity.current
        val posterBucket = artworkBucket(with(density) { posterWidth.toPx() })
        val scrollStep = with(density) { (if (compact) 72.dp else 120.dp).toPx() }

        MovieDetailsBackdrop(movie.artPath, posters, enabled)
        Column(
            Modifier.fillMaxSize().padding(horizontal = edge, vertical = if (compact) 14.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CinemaButton(
                    onClick = onDismiss,
                    enabled = enabled,
                    modifier = Modifier.focusRequester(backFocus)
                        .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Back }
                        .focusProperties {
                            up = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = readingFocus
                            down = readingFocus
                        },
                ) { DetailActionText("‹  Back to movies", compact) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (readingFocused) "↑ ↓ Scroll details" else "↑ Read synopsis & credits",
                    color = CinemaColors.Muted,
                    fontSize = if (compact) 11.sp else 14.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 40.dp),
            ) {
                PosterArtwork(
                    path = movie.thumbPath,
                    posters = posters,
                    width = posterBucket,
                    load = enabled,
                    modifier = Modifier.width(posterWidth).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(if (compact) 6.dp else 10.dp)),
                    title = movie.title,
                    metadataLoading = loading,
                )
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .focusRequester(readingFocus)
                        .focusProperties {
                            canFocus = enabled
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = backFocus
                            down = playFocus
                        }
                        .onFocusChanged {
                            readingFocused = it.hasFocus
                            if (it.hasFocus) lastFocus = DetailFocus.Reader
                        }
                        .onPreviewKeyEvent { event ->
                            if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (scroll.canScrollBackward) scope.launch { scroll.scrollBy(-scrollStep) }
                                    else backFocus.requestFocus()
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (scroll.canScrollForward) scope.launch { scroll.scrollBy(scrollStep) }
                                    else playFocus.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .semantics { contentDescription = "Movie details. Use up and down to read synopsis and credits." }
                        .focusable(enabled)
                        .drawWithContent {
                            drawContent()
                            if (readingFocused) {
                                drawLine(
                                    CinemaColors.Accent.copy(alpha = 0.65f),
                                    Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx(),
                                )
                            }
                            if (scroll.maxValue > 0) {
                                val track = size.height
                                val thumb = (track * track / (track + scroll.maxValue)).coerceAtLeast(18.dp.toPx())
                                val top = (track - thumb) * scroll.value.toFloat() / scroll.maxValue
                                drawRoundRect(
                                    CinemaColors.Muted.copy(alpha = if (readingFocused) 0.5f else 0.2f),
                                    topLeft = Offset(size.width - 2.dp.toPx(), top),
                                    size = Size(2.dp.toPx(), thumb),
                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                )
                            }
                        },
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll, enabled = enabled)
                            .padding(start = 14.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
                    ) {
                        Text(
                            movie.title,
                            color = CinemaColors.Foreground,
                            fontSize = if (compact) 26.sp else 44.sp,
                            lineHeight = if (compact) 31.sp else 50.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (movie.tagline.isNotBlank()) Text(
                            movie.tagline,
                            color = CinemaColors.Accent,
                            fontSize = if (compact) 15.sp else 21.sp,
                            lineHeight = if (compact) 21.sp else 29.sp,
                            fontWeight = FontWeight.Light,
                        )
                        if (metadata.isNotBlank()) Text(
                            metadata, color = CinemaColors.Muted,
                            fontSize = if (compact) 13.sp else 16.sp,
                            lineHeight = if (compact) 20.sp else 25.sp,
                        )
                        if (movie.genres.isNotEmpty()) Text(
                            movie.genres.joinToString("  ·  "), color = CinemaColors.Accent,
                            fontSize = if (compact) 13.sp else 16.sp,
                            lineHeight = if (compact) 20.sp else 25.sp,
                        )
                        if (loading) Text(
                            "Refreshing movie details…", color = CinemaColors.Muted,
                            fontSize = if (compact) 12.sp else 14.sp,
                        )
                        if (error != null) Text(
                            "Couldn’t refresh movie details. $error",
                            color = CinemaColors.Error,
                            fontSize = if (compact) 13.sp else 16.sp,
                            lineHeight = if (compact) 20.sp else 25.sp,
                        )
                        DetailSection(
                            "SYNOPSIS",
                            movie.summary.ifBlank { if (loading) "Loading synopsis…" else "No synopsis supplied by Plex." },
                            compact,
                        )
                        if (movie.directors.isNotEmpty()) DetailSection(
                            if (movie.directors.size == 1) "DIRECTOR" else "DIRECTORS",
                            movie.directors.joinToString("  ·  "), compact,
                        )
                        if (movie.cast.isNotEmpty()) DetailSection("CAST", movie.cast.joinToString("  ·  "), compact)
                        if (!loading && movie.directors.isEmpty() && movie.cast.isEmpty()) Text(
                            "No credits supplied by Plex.", color = CinemaColors.Muted,
                            fontSize = if (compact) 12.sp else 14.sp,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                if (resume) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${detailRuntime(movie.viewOffsetMs)}" +
                                if (movie.durationMs > 0) " of ${detailRuntime(movie.durationMs)}" else " watched",
                            color = CinemaColors.Muted,
                            fontSize = if (compact) 11.sp else 13.sp,
                        )
                        if (movie.durationMs > 0) Box(
                            Modifier.padding(start = 16.dp).weight(1f).height(2.dp)
                                .background(CinemaColors.Foreground.copy(alpha = 0.12f)),
                        ) {
                            Box(Modifier.fillMaxWidth((movie.viewOffsetMs.toFloat() / movie.durationMs).coerceIn(0f, 1f))
                                .height(2.dp).background(CinemaColors.Accent.copy(alpha = 0.7f)))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinemaButton(
                        onClick = { onPlay(false) }, enabled = enabled, selected = true,
                        modifier = Modifier.focusRequester(playFocus)
                            .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Play }
                            .focusProperties {
                                up = readingFocus
                                down = FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                if (!resume && error == null) right = FocusRequester.Cancel
                            },
                    ) { DetailActionText(if (resume) "Resume · ${detailRuntime(movie.viewOffsetMs)}" else "Play", compact) }
                    if (resume) CinemaButton(
                        onClick = { onPlay(true) }, enabled = enabled,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.StartOver }
                            .focusProperties {
                                up = readingFocus
                                down = FocusRequester.Cancel
                                if (error == null) right = FocusRequester.Cancel
                            },
                    ) { DetailActionText("Start over", compact) }
                    if (error != null) CinemaButton(
                        onClick = onRetry, enabled = enabled,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Retry }
                            .focusProperties {
                                up = readingFocus
                                down = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            },
                    ) { DetailActionText("Retry details", compact) }
                    if (!compact) {
                        Spacer(Modifier.weight(1f))
                        Text("Original quality · Direct playback", color = CinemaColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(movie.ratingKey, enabled) {
        if (enabled) {
            withFrameNanos { }
            playFocus.requestFocus()
        }
    }
    // Capture removal before applying changes can move focus to Back and overwrite lastFocus.
    val focusedActionRemoved = (lastFocus == DetailFocus.Retry && error == null) ||
        (lastFocus == DetailFocus.StartOver && !resume)
    LaunchedEffect(movie.ratingKey, enabled, resume, error != null) {
        if (enabled && focusedActionRemoved) {
            withFrameNanos { }
            playFocus.requestFocus()
        }
    }
}

private enum class DetailFocus { Back, Reader, Play, StartOver, Retry }

@Composable
private fun MovieDetailsBackdrop(path: String?, posters: PosterRepository, active: Boolean) {
    val painter = rememberMovieArtworkPainter(path, posters, width = 1280, height = 720, active = active)
    val opacity = animateFloatAsState(
        targetValue = if (painter != null) 0.5f else 0f,
        animationSpec = tween(300),
        label = "detail backdrop",
    )
    if (painter != null) Image(
        painter = painter, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
    )
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
        CinemaColors.Canvas.copy(alpha = 0.9f),
        CinemaColors.Canvas.copy(alpha = 0.72f),
        CinemaColors.Canvas.copy(alpha = 0.36f),
    ))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Color.Transparent, CinemaColors.Canvas.copy(alpha = 0.6f), CinemaColors.Canvas,
    ))))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailSection(label: String, text: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
        Text(label, color = CinemaColors.Muted, fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 1.5.sp)
        Text(
            text, color = CinemaColors.Foreground.copy(alpha = 0.92f),
            fontSize = if (compact) 15.sp else 19.sp,
            lineHeight = if (compact) 23.sp else 30.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailActionText(text: String, compact: Boolean) {
    Text(text, color = CinemaColors.Foreground, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium)
}

private fun detailRuntime(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    if (minutes == 0L) return "${milliseconds / 1_000}s"
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

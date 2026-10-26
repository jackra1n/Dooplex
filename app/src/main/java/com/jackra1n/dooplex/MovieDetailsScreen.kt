package com.jackra1n.dooplex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Artwork-led movie destination with fitted Plex title/backdrop art and a two-line hero summary.
 * Provider badges prefer RT audience; one HDR/audio label is shown while the reader retains detail.
 * Black readability gradients keep artwork clear; compact layouts reserve space for every action.
 * D-pad Down opens the reader entry point from playback; selecting it focuses the scrollable text.
 * Reader Up/Down reach its return control at either boundary; Back returns to the hero reading link.
 * Metadata refresh never resets focus; removing Retry/Start over returns focus to playback.
 * Playback delegates unchanged to the owner's close-before-play callback; artwork is cached.
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
    val startFocus = remember(movie.ratingKey) { FocusRequester() }
    val retryFocus = remember(movie.ratingKey) { FocusRequester() }
    val backFocus = remember(movie.ratingKey) { FocusRequester() }
    val moreFocus = remember(movie.ratingKey) { FocusRequester() }
    val readingFocus = remember(movie.ratingKey) { FocusRequester() }
    val readingBackFocus = remember(movie.ratingKey) { FocusRequester() }
    val scroll = remember(movie.ratingKey) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    var reading by remember(movie.ratingKey) { mutableStateOf(false) }
    var readingFocused by remember(movie.ratingKey) { mutableStateOf(false) }
    var lastFocus by remember(movie.ratingKey) { mutableStateOf(DetailFocus.Play) }
    val resume = movie.viewOffsetMs > 0 && (movie.durationMs <= 0 || movie.viewOffsetMs < movie.durationMs)
    val metadata = remember(movie) {
        listOfNotNull(
            movie.subtitle.takeIf { it.isNotBlank() },
            movie.durationMs.takeIf { it > 0 }?.let(::detailRuntime),
            movie.contentRating.takeIf { it.isNotBlank() },
            "Watched".takeIf { movie.watched },
        ).joinToString("  ·  ")
    }
    val genres = remember(movie.genres) { movie.genres.joinToString("  ·  ") }
    val ratings = remember(movie.ratings) {
        movie.ratings.filter { it.value.isFinite() && it.value in 0.0..10.0 }.mapNotNull { rating ->
            when (rating.source) {
                "IMDb" -> "IMDb ${String.format(Locale.ROOT, "%.1f", rating.value)}/10"
                "Rotten Tomatoes" -> when (rating.type) {
                    "critic" -> "Rotten Tomatoes critics ${(rating.value * 10).roundToInt()}%"
                    "audience" -> "Rotten Tomatoes audience ${(rating.value * 10).roundToInt()}%"
                    else -> null
                }
                else -> null
            }
        }.distinct().joinToString("  ·  ")
    }
    val formats = remember(movie.videoFormat, movie.audioFormat, movie.videoResolution) {
        listOf(
            movie.videoFormat.ifBlank { movie.videoResolution.uppercase(Locale.ROOT) },
            movie.audioFormat,
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
    }
    val heroRatings = remember(movie.ratings) {
        val valid = movie.ratings.filter { it.value.isFinite() && it.value in 0.0..10.0 }
        listOfNotNull(
            valid.firstOrNull { it.source == "IMDb" && it.type == "audience" }
                ?: valid.firstOrNull { it.source == "IMDb" },
            valid.firstOrNull { it.source == "Rotten Tomatoes" && it.type == "audience" }
                ?: valid.firstOrNull { it.source == "Rotten Tomatoes" },
        )
    }
    val heroFormats = remember(movie.videoFormat, movie.audioFormat, movie.videoResolution) {
        val video = movie.videoFormat.split(" · ")
        val range = listOf("Dolby Vision", "HDR10+", "HDR10", "HLG", "HDR").firstOrNull { it in video }
        val resolution = video.firstOrNull { it.isNotBlank() && it !in listOf("Dolby Vision", "HDR10+", "HDR10", "HLG", "HDR") }
            ?: movie.videoResolution.uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
        val audio = movie.audioFormat.split(" · ")
        val audioLabel = when {
            "Atmos" in audio -> "Atmos"
            "DTS:X" in audio -> "DTS:X"
            else -> audio.firstOrNull()?.takeIf { it.isNotBlank() }?.let { codec ->
                val short = when (codec) {
                    "Dolby TrueHD" -> "TrueHD"
                    "Dolby Digital Plus" -> "DD+"
                    "Dolby Digital" -> "DD"
                    else -> codec
                }
                val channels = audio.lastOrNull()?.takeIf { it != codec }
                listOfNotNull(short, channels).joinToString(" ")
            }
        }
        listOfNotNull(resolution, range, audioLabel).joinToString("  ·  ")
    }
    val synopsis = movie.summary.ifBlank { if (loading) "Loading synopsis…" else "No synopsis supplied by Plex." }
    // Capture this before removal can move focus and overwrite the identity of the removed action.
    val focusedActionRemoved = (lastFocus == DetailFocus.Retry && error == null) ||
        (lastFocus == DetailFocus.StartOver && !resume)

    BackHandler(enabled = enabled) {
        if (reading) reading = false else onDismiss()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val compact = maxHeight < 500.dp || maxWidth < 900.dp
        val edge = if (compact) 24.dp else 56.dp
        val verticalEdge = if (compact) 18.dp else if (maxHeight < 700.dp) 24.dp else 40.dp
        val spacious = maxHeight >= 700.dp
        val logoHeight = if (compact) 40.dp else if (spacious) 104.dp else 64.dp
        val density = LocalDensity.current
        val scrollStep = with(density) { (if (compact) 76.dp else 128.dp).toPx() }

        MovieDetailsBackdrop(movie.artPath, posters, enabled)
        if (!reading) {
            Column(Modifier.fillMaxSize().padding(horizontal = edge, vertical = verticalEdge)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CinemaButton(
                        onClick = onDismiss,
                        enabled = enabled,
                        modifier = Modifier.focusRequester(backFocus)
                            .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Back }
                            .focusProperties {
                                up = FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = playFocus
                                down = playFocus
                            },
                    ) { DetailActionText(if (compact) "‹  Back" else "‹  Back to library", compact) }
                    Spacer(Modifier.weight(1f))
                    if (loading || error != null) Text(
                        if (loading) "Updating details…" else "Details unavailable · Retry below",
                        color = CinemaColors.Muted,
                        fontSize = if (compact) 11.sp else 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 32.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(
                        Modifier.weight(if (compact) 0.73f else 0.67f),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                    ) {
                        DetailTitle(movie, posters, enabled, compact, logoHeight)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (metadata.isNotBlank()) Text(
                                metadata,
                                color = CinemaColors.Muted,
                                fontSize = if (compact) 11.sp else 13.sp,
                                lineHeight = if (compact) 14.sp else 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            heroRatings.forEach { DetailRatingBadge(it, compact) }
                        }
                        if (heroFormats.isNotBlank()) Text(
                            heroFormats,
                            color = CinemaColors.Foreground.copy(alpha = 0.86f),
                            fontSize = if (compact) 11.sp else 13.sp,
                            lineHeight = if (compact) 14.sp else 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            synopsis,
                            color = CinemaColors.Foreground.copy(alpha = 0.92f),
                            fontSize = if (compact) 12.sp else 16.sp,
                            lineHeight = if (compact) 15.sp else 24.sp,
                            maxLines = if (spacious) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier.padding(top = if (compact) 2.dp else 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CinemaButton(
                                onClick = { onPlay(false) }, enabled = enabled, primary = true,
                                modifier = Modifier.focusRequester(playFocus)
                                    .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Play }
                                    .focusProperties {
                                        up = backFocus
                                        down = moreFocus
                                        left = FocusRequester.Cancel
                                        right = if (resume) startFocus else FocusRequester.Cancel
                                    },
                            ) {
                                DetailPlayIcon(compact)
                                DetailActionText(
                                    if (!resume) "Play" else if (compact) "Resume" else "Resume · ${detailRuntime(movie.viewOffsetMs)}",
                                    compact,
                                )
                            }
                            if (resume) CinemaButton(
                                onClick = { onPlay(true) }, enabled = enabled,
                                modifier = Modifier.focusRequester(startFocus)
                                    .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.StartOver }
                                    .focusProperties {
                                        up = backFocus
                                        down = if (error != null) retryFocus else moreFocus
                                        left = playFocus
                                        right = FocusRequester.Cancel
                                    },
                            ) { DetailActionText("Start over", compact) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp)) {
                            DetailReadingLink(
                                text = if (compact) "Synopsis & credits  ›" else "Read synopsis & credits  ›",
                                compact = compact,
                                enabled = enabled,
                                onClick = { reading = true },
                                modifier = Modifier.focusRequester(moreFocus)
                                    .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.More }
                                    .focusProperties {
                                        up = playFocus
                                        down = FocusRequester.Cancel
                                        left = FocusRequester.Cancel
                                        right = if (error != null) retryFocus else FocusRequester.Cancel
                                    },
                            )
                            if (error != null) DetailReadingLink(
                                text = "Retry details",
                                compact = compact,
                                enabled = enabled,
                                onClick = onRetry,
                                modifier = Modifier.focusRequester(retryFocus)
                                    .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.Retry }
                                    .focusProperties {
                                        up = if (resume) startFocus else playFocus
                                        down = FocusRequester.Cancel
                                        left = moreFocus
                                        right = FocusRequester.Cancel
                                    },
                            )
                        }
                    }
                    DetailHeroCredits(movie, compact, Modifier.weight(if (compact) 0.27f else 0.33f))
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)))
            Column(
                Modifier.fillMaxSize().padding(horizontal = edge, vertical = verticalEdge),
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CinemaButton(
                        onClick = { reading = false }, enabled = enabled,
                        modifier = Modifier.focusRequester(readingBackFocus)
                            .onFocusChanged { if (it.isFocused) lastFocus = DetailFocus.ReaderBack }
                            .focusProperties {
                                up = FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = readingFocus
                                down = readingFocus
                            },
                    ) { DetailActionText("‹  Back to movie", compact) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "↑ ↓ Read  ·  Back to return",
                        color = CinemaColors.Muted,
                        fontSize = if (compact) 11.sp else 14.sp,
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(if (compact) 1f else 0.76f)
                        .focusRequester(readingFocus)
                        .focusProperties {
                            canFocus = enabled
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = readingBackFocus
                            down = readingBackFocus
                        }
                        .onFocusChanged {
                            readingFocused = it.isFocused
                            if (it.isFocused) lastFocus = DetailFocus.Reader
                        }
                        .onPreviewKeyEvent { event ->
                            if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (scroll.canScrollBackward) scope.launch { scroll.scrollBy(-scrollStep) }
                                    else readingBackFocus.requestFocus()
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (scroll.canScrollForward) scope.launch { scroll.scrollBy(scrollStep) }
                                    else readingBackFocus.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .semantics { contentDescription = "Full movie synopsis and credits. Use up and down to scroll; Back returns to the movie." }
                        .focusable(enabled)
                        .drawWithContent {
                            drawContent()
                            if (readingFocused) drawLine(
                                CinemaColors.Foreground.copy(alpha = 0.55f),
                                Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx(),
                            )
                            if (scroll.maxValue > 0) {
                                val thumb = (size.height * size.height / (size.height + scroll.maxValue))
                                    .coerceAtLeast(18.dp.toPx())
                                val top = (size.height - thumb) * scroll.value.toFloat() / scroll.maxValue
                                drawRoundRect(
                                    CinemaColors.Muted.copy(alpha = if (readingFocused) 0.6f else 0.25f),
                                    topLeft = Offset(size.width - 2.dp.toPx(), top),
                                    size = Size(2.dp.toPx(), thumb),
                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                )
                            }
                        },
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll, enabled = enabled)
                            .padding(start = if (compact) 16.dp else 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
                    ) {
                        Text(
                            movie.title, color = CinemaColors.Foreground,
                            fontSize = if (compact) 24.sp else 34.sp,
                            lineHeight = if (compact) 31.sp else 42.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (movie.tagline.isNotBlank()) Text(
                            movie.tagline, color = CinemaColors.Muted,
                            fontSize = if (compact) 14.sp else 19.sp,
                            lineHeight = if (compact) 22.sp else 29.sp,
                        )
                        DetailSection("SYNOPSIS", synopsis, compact)
                        Spacer(Modifier.fillMaxWidth().height(1.dp).background(CinemaColors.Foreground.copy(alpha = 0.12f)))
                        Text(
                            "Credits", color = CinemaColors.Foreground,
                            fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Medium,
                        )
                        if (movie.directors.isNotEmpty()) DetailSection(
                            if (movie.directors.size == 1) "DIRECTOR" else "DIRECTORS",
                            movie.directors.joinToString("  ·  "), compact,
                        )
                        if (movie.cast.isNotEmpty()) DetailSection("CAST", movie.cast.joinToString("  ·  "), compact)
                        if (movie.directors.isEmpty() && movie.cast.isEmpty()) Text(
                            if (loading) "Loading credits…" else "No credits supplied by Plex.",
                            color = CinemaColors.Muted, fontSize = if (compact) 14.sp else 17.sp,
                        )
                        if (genres.isNotBlank()) DetailSection("GENRES", genres, compact)
                        if (metadata.isNotBlank()) DetailSection("DETAILS", metadata, compact)
                        if (ratings.isNotBlank()) DetailSection("RATINGS", ratings, compact)
                        if (formats.isNotBlank()) DetailSection("SOURCE FORMATS", formats, compact)
                        if (error != null) DetailSection(
                            "DETAILS UNAVAILABLE", "Couldn’t refresh movie details. $error\nReturn to the movie to retry.", compact,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(movie.ratingKey, enabled, reading) {
        if (enabled) {
            withFrameNanos { }
            when {
                reading -> readingFocus.requestFocus()
                lastFocus == DetailFocus.Reader || lastFocus == DetailFocus.ReaderBack -> moreFocus.requestFocus()
                else -> playFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(movie.ratingKey, enabled, resume, error != null) {
        if (enabled && !reading && focusedActionRemoved) {
            withFrameNanos { }
            playFocus.requestFocus()
        }
    }
}

private enum class DetailFocus { Back, More, Reader, ReaderBack, Play, StartOver, Retry }

@Composable
private fun MovieDetailsBackdrop(path: String?, posters: PosterRepository, active: Boolean) {
    val painter = rememberMovieArtworkPainter(path, posters, width = 1280, height = 720, active = active)
    val opacity = animateFloatAsState(
        targetValue = if (painter != null) 1f else 0f,
        animationSpec = tween(300),
        label = "detail backdrop",
    )
    Box(Modifier.fillMaxSize().background(Color.Black))
    if (painter != null) Image(
        painter = painter, contentDescription = null, contentScale = ContentScale.Fit,
        alignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
    )
    // Only the left text zone needs a side gradient; the upper/right picture remains untouched.
    Box(Modifier.fillMaxWidth(0.72f).fillMaxSize().background(Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.62f),
        0.48f to Color.Black.copy(alpha = 0.35f),
        1f to Color.Transparent,
    )))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Transparent,
        0.28f to Color.Transparent,
        0.64f to Color.Black.copy(alpha = 0.48f),
        1f to Color.Black,
    )))
}

/** Title art is fitted, never cropped; the real title remains readable until artwork is available. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailTitle(
    movie: PlexBrowseItem,
    posters: PosterRepository,
    active: Boolean,
    compact: Boolean,
    height: androidx.compose.ui.unit.Dp,
) {
    val logo = rememberMovieArtworkPainter(movie.logoPath, posters, width = 800, height = 310, active = active)
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        if (logo != null) Image(
            painter = logo,
            contentDescription = movie.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxSize(),
        ) else Text(
            movie.title,
            color = CinemaColors.Foreground,
            fontSize = if (compact) 18.sp else 30.sp,
            lineHeight = if (compact) 20.sp else 32.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailHeroCredits(movie: PlexBrowseItem, compact: Boolean, modifier: Modifier) {
    Column(
        modifier.padding(bottom = if (compact) 34.dp else 42.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp),
    ) {
        if (movie.directors.isNotEmpty()) DetailHeroCredit(
            "Directed by", movie.directors.take(if (compact) 1 else 2).joinToString(", "), compact,
        )
        if (movie.cast.isNotEmpty()) DetailHeroCredit(
            "Starring", movie.cast.take(if (compact) 2 else 3).joinToString(", "), compact,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailHeroCredit(label: String, names: String, compact: Boolean) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = CinemaColors.Muted, fontSize = if (compact) 10.sp else 12.sp)
        Text(
            names,
            color = CinemaColors.Foreground,
            fontSize = if (compact) 11.sp else 14.sp,
            lineHeight = if (compact) 14.sp else 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = if (compact) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Provider marks identify the source without repeating long labels in the hero. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailRatingBadge(rating: PlexMediaRating, compact: Boolean) {
    val imdb = rating.source == "IMDb"
    val score = if (imdb) String.format(Locale.ROOT, "%.1f", rating.value)
        else "${(rating.value * 10).roundToInt()}%"
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "${rating.source} ${rating.type} $score${if (imdb) " out of 10" else ""}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(if (imdb) R.drawable.ic_imdb else R.drawable.ic_rotten_tomatoes),
            contentDescription = null,
            modifier = if (imdb) Modifier.width(if (compact) 28.dp else 32.dp).height(16.dp)
                else Modifier.size(if (compact) 15.dp else 17.dp),
        )
        Text(score, color = CinemaColors.Foreground, fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A vector triangle avoids depending on whichever play glyph happens to exist in the TV font. */
@Composable
private fun DetailPlayIcon(compact: Boolean) {
    val color = LocalContentColor.current
    Box(Modifier.size(if (compact) 12.dp else 15.dp).drawWithCache {
        val triangle = Path().apply {
            moveTo(size.width * 0.18f, 0f)
            lineTo(size.width, size.height * 0.5f)
            lineTo(size.width * 0.18f, size.height)
            close()
        }
        onDrawBehind { drawPath(triangle, color) }
    })
}

/** A quiet text action with a stable, high-contrast D-pad underline rather than another pill. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailReadingLink(
    text: String,
    compact: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = if (focused) CinemaColors.Foreground else CinemaColors.Muted,
        fontSize = if (compact) 11.sp else 14.sp,
        fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                if (focused) drawLine(
                    CinemaColors.Foreground, Offset(0f, size.height - 2.dp.toPx()),
                    Offset(size.width, size.height - 2.dp.toPx()), strokeWidth = 1.dp.toPx(),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 9.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailSection(label: String, text: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Text(label, color = CinemaColors.Muted, fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 1.5.sp)
        Text(
            text, color = CinemaColors.Foreground.copy(alpha = 0.92f),
            fontSize = if (compact) 15.sp else 19.sp,
            lineHeight = if (compact) 24.sp else 31.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailActionText(text: String, compact: Boolean) {
    Text(text, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Medium)
}

private fun detailRuntime(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    if (minutes == 0L) return "${milliseconds / 1_000}s"
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

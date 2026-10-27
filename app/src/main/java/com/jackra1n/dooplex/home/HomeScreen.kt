package com.jackra1n.dooplex.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.artwork.MovieBackdrop
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.artworkBucket
import com.jackra1n.dooplex.artwork.rememberMovieArtworkPainter
import com.jackra1n.dooplex.details.DetailPlayIcon
import com.jackra1n.dooplex.details.MovieDetailsScreen
import com.jackra1n.dooplex.diagnostics.Analytics
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.CinemaButton
import com.jackra1n.dooplex.ui.CollectionFocusState
import com.jackra1n.dooplex.ui.CollectionPosterCard
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.WhenVisible
import com.jackra1n.dooplex.ui.theme.CinemaColors
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HERO_SECONDS = 8L
private val ShelfSpring = spring<Float>(dampingRatio = 1f, stiffness = 170f)

/**
 * Home: a rotating hero (art, logo, facts, synopsis, Play and Details) over the server's
 * shelves. Focus in the shelves rests the focused row on one line under the bar, reached on the
 * same soft spring the library grid uses; each shelf returns to the card it last left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: HomeState,
    posters: PosterRepository,
    navigation: @Composable () -> Unit,
    focus: CollectionFocusState,
    enabled: Boolean,
    onPlay: (PlexBrowseItem, Boolean) -> Unit,
    onOpen: (PlexBrowseItem) -> Unit,
    onBack: () -> Unit,
    onReady: () -> Unit,
) {
    // Rows are built during idle frames before Down reveals them, and kept a little after they leave.
    val listState = rememberLazyListState(cacheWindow = remember { LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f) })
    val scope = rememberCoroutineScope()
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    val heroPlay = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    var focusedCard by remember { mutableStateOf<PlexBrowseItem?>(null) }
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<PlexBrowseItem?>(null) }
    val heroes = state.heroes
    val hero = heroes.getOrNull(heroIndex.mod(heroes.size.coerceAtLeast(1)))
    val selected = state.selectedMovie

    fun page(step: Int) {
        if (heroes.size > 1) heroIndex = (heroIndex + step).mod(heroes.size)
    }

    fun open(item: PlexBrowseItem) {
        when (item.type) {
            "movie" -> state.selectMovie(item)
            "episode" -> onPlay(item, false)
            else -> onOpen(item)
        }
    }

    // A title changes only once its art and logo are ready, so text and picture swap together.
    LaunchedEffect(hero?.ratingKey) {
        val next = hero ?: return@LaunchedEffect
        val details = state.heroDetails(next)
        listOfNotNull(details.artPath?.let { Triple(it, 1280, 720) }, details.logoPath?.let { Triple(it, 800, 310) })
            .forEach { (path, width, height) ->
                try {
                    posters.load(path, width, height)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // A missing picture falls back to the title text and the page wash.
                }
            }
        Analytics.mark("hero:swap")
        shown = details
    }
    WhenVisible { visible ->
        val interactive = enabled && visible
        LaunchedEffect(heroIndex, heroes.size, interactive) {
            if (!interactive || heroes.size < 2) return@LaunchedEffect
            heroes.getOrNull((heroIndex + 1).mod(heroes.size))?.let { state.heroDetails(it) }
            delay(HERO_SECONDS * 1000)
            if (listState.firstVisibleItemIndex == 0) page(1)
        }
    }
    fun focusHero() {
        scope.launch {
            if (listState.firstVisibleItemIndex != 0) listState.animateScrollToItem(0)
            withFrameNanos { }
            if (shown != null) heroPlay.requestFocus() else content.requestFocus()
        }
    }

    // The first focus waits for the hero when there is one; later returns restore the last card.
    WhenVisible { visible ->
        val interactive = enabled && visible
        LaunchedEffect(interactive, focus.navigationType) {
            if (!interactive || focus.navigationType != null || state.selectedMovie != null) return@LaunchedEffect
            snapshotFlow { shown != null || !state.loading && state.heroes.isEmpty() || state.error != null }.first { it }
            withFrameNanos { }
            if (!focusedOnce && shown != null) heroPlay.requestFocus() else content.requestFocus()
            focusedOnce = true
        }
    }
    LaunchedEffect(focus.headerEpoch) {
        if (focus.headerRoute != "home") return@LaunchedEffect
        focusHero()
        focus.completeHeader("home")
    }
    LaunchedEffect(focus.contentEpoch) {
        if (focus.contentType != "home") return@LaunchedEffect
        focusHero()
        focus.completeContent("home")
    }
    WhenVisible { visible ->
        val interactive = enabled && visible
        BackHandler(enabled = interactive && selected == null) {
            if (!heroFocused && shown != null) focusHero() else onBack()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().then(if (selected != null) Modifier.clearAndSetSemantics { } else Modifier)) {
        val density = LocalDensity.current
        val edge = 56.dp
        val columns = 7
        val cardWidth = (maxWidth - edge * 2 - 18.dp * (columns - 1)) / columns
        val artworkWidth = artworkBucket(with(density) { cardWidth.toPx() })
        val heroHeight = if (shown != null) maxHeight * 0.8f else maxHeight * 0.3f
        val heroHeightPx = with(density) { heroHeight.toPx() }
        val restLine = with(density) { 152.dp.toPx() }
        val shelfSpec = remember(listState, restLine, heroHeightPx) {
            object : BringIntoViewSpec {
                @Suppress("OVERRIDE_DEPRECATION")
                override val scrollAnimationSpec: AnimationSpec<Float> = ShelfSpring

                // A burst of Up presses can focus the hero while it is still rows above the screen.
                // Shelves share one height, so the way back to the top is known from the first one shown.
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    if (!heroFocused) return offset - restLine
                    val first = listState.firstVisibleItemIndex
                    val above = listState.firstVisibleItemScrollOffset.toFloat()
                    if (first == 0) return -above
                    val shelf = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f
                    return -(heroHeightPx + (first - 1) * shelf + above)
                }
            }
        }
        // Compose pivots TV focus to 30% of a row; a shelf only scrolls when its card would cross the
        // page margins, so the first card returns exactly to its resting place.
        val edgePx = with(density) { edge.toPx() }
        val rowSpec = remember(edgePx) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                    offset < edgePx -> offset - edgePx
                    offset + size > containerSize - edgePx -> offset + size - (containerSize - edgePx)
                    else -> 0f
                }
            }
        }
        // Ready once the hero's picture and the first shelf's posters are in memory, so the start
        // screen can lift onto a finished page instead of one that is still filling in.
        LaunchedEffect(state) {
            snapshotFlow { !state.loading && (shown != null || state.heroes.isEmpty() || state.error != null) }.first { it }
            withTimeoutOrNull(1_500) {
                coroutineScope {
                    state.rows.firstOrNull()?.items?.take(columns + 1)?.mapNotNull { it.thumbPath }?.forEach { path ->
                        launch {
                            try {
                                posters.load(path, artworkWidth, artworkWidth * 3 / 2)
                            } catch (_: IOException) {
                            }
                        }
                    }
                }
            }
            withFrameNanos { }
            onReady()
        }

        MovieBackdrop(
            movie = { if (heroFocused || focusedCard == null) shown else focusedCard },
            posters = posters,
            artworkWidth = artworkWidth,
            active = enabled,
            modifier = Modifier.fillMaxSize(),
        )
        HeroArt(shown, posters, Modifier.fillMaxSize().graphicsLayer {
            translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -heroHeightPx * 2
        })
        CompositionLocalProvider(LocalBringIntoViewSpec provides shelfSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 64.dp),
                modifier = Modifier.fillMaxSize().focusRequester(content).focusRestorer(),
            ) {
                item(key = "hero", contentType = "hero") {
                    Box(Modifier.fillMaxWidth().height(heroHeight).onFocusChanged { heroFocused = it.hasFocus }) {
                        val item = shown
                        if (item != null) HeroDetails(
                            item = item,
                            posters = posters,
                            heroes = heroes.size,
                            current = heroIndex,
                            enabled = enabled,
                            playFocus = heroPlay,
                            onPlay = { if (item.type == "movie") onPlay(item, false) else onOpen(item) },
                            onInfo = { open(item) },
                            onPage = ::page,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = edge, bottom = 20.dp),
                        ) else HomeStatus(state, enabled, Modifier.align(Alignment.BottomStart).padding(start = edge, bottom = 16.dp))
                    }
                }
                items(state.rows, key = { it.key }, contentType = { "shelf" }) { row ->
                    Column(Modifier.padding(top = 12.dp, bottom = 14.dp)) {
                        Text(row.title, color = CinemaColors.Foreground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = edge, bottom = 12.dp))
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                            LazyRow(
                                state = rememberLazyListState(cacheWindow = remember { LazyLayoutCacheWindow(aheadFraction = 0.5f) }),
                                contentPadding = PaddingValues(horizontal = edge),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier.fillMaxWidth().focusRestorer(),
                            ) {
                                items(row.items, key = { it.ratingKey }, contentType = { "card" }) { item ->
                                    CollectionPosterCard(
                                        item = item,
                                        posters = posters,
                                        artworkWidth = artworkWidth,
                                        loadArtwork = enabled,
                                        enabled = enabled,
                                        modifier = Modifier.width(cardWidth),
                                        onFocus = { focusedCard = item },
                                        onClick = { open(item) },
                                        placeholderLabel = item.title,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth()) { navigation() }
    }

    if (selected != null) {
        MovieDetailsScreen(
            movie = selected,
            posters = posters,
            loading = state.detailsLoading,
            error = state.detailsError,
            enabled = enabled,
            onDismiss = {
                state.closeDetails()
                content.requestFocus()
            },
            onRetry = { state.selectMovie(selected) },
            onPlay = { fromBeginning ->
                state.closeDetails()
                onPlay(selected, fromBeginning)
            },
        )
    }
}

/** The hero's picture fades out at the bottom into the page wash, and darkens behind the text. */
@Composable
private fun HeroArt(item: PlexBrowseItem?, posters: PosterRepository, modifier: Modifier) {
    Crossfade(targetState = item, animationSpec = tween(500), modifier = modifier, label = "hero art") { hero ->
        val painter = rememberMovieArtworkPainter(hero?.artPath, posters, width = 1280, height = 720, active = true)
        if (painter != null) Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val side = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.72f), 0.42f to Color.Black.copy(alpha = 0.4f), 0.75f to Color.Transparent,
                    )
                    val fade = Brush.verticalGradient(0.5f to Color.Black, 0.98f to Color.Transparent)
                    onDrawWithContent {
                        drawContent()
                        drawRect(side)
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HeroDetails(
    item: PlexBrowseItem,
    posters: PosterRepository,
    heroes: Int,
    current: Int,
    enabled: Boolean,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onPage: (Int) -> Unit,
    modifier: Modifier,
) {
    val logo = rememberMovieArtworkPainter(item.logoPath, posters, width = 800, height = 310, active = enabled)
    val facts = remember(item) {
        listOfNotNull(
            if (item.type == "show") "TV Show" else "Movie",
            item.subtitle.takeIf { it.isNotBlank() },
            item.contentRating.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    Column(modifier.widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (logo != null) Image(
            logo, contentDescription = item.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
            modifier = Modifier.height(110.dp).widthIn(max = 460.dp).fillMaxWidth(),
        ) else Text(item.title, color = CinemaColors.Foreground, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(facts, color = CinemaColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (item.summary.isNotBlank()) Text(item.summary, color = CinemaColors.Foreground.copy(alpha = 0.9f),
            fontSize = 16.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CinemaButton(
                onClick = onPlay, enabled = enabled, primary = true,
                modifier = Modifier.focusRequester(playFocus).onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionLeft || heroes < 2) false
                    else {
                        if (event.type == KeyEventType.KeyDown) onPage(-1)
                        true
                    }
                },
            ) {
                DetailPlayIcon(compact = false)
                Text(
                    when {
                        item.type == "show" -> "Open"
                        item.viewOffsetMs > 0 -> "Continue"
                        else -> "Play"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
            }
            CinemaButton(
                onClick = onInfo, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Details" }.onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionRight || heroes < 2) false
                    else {
                        if (event.type == KeyEventType.KeyDown) onPage(1)
                        true
                    }
                },
            ) {
                Text("i", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (heroes > 1) Text("›", color = CinemaColors.Foreground.copy(alpha = 0.7f), fontSize = 28.sp)
        }
        if (heroes > 1) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(heroes) { index ->
                Box(
                    Modifier.size(width = if (index == current) 18.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(CinemaColors.Foreground.copy(alpha = if (index == current) 0.95f else 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun HomeStatus(state: HomeState, enabled: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when {
                state.error != null -> "Home couldn’t be loaded"
                state.loading -> "Loading Home…"
                else -> "Nothing new on this server yet"
            },
            color = CinemaColors.Foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
        )
        state.error?.let { Text(it, color = CinemaColors.Error, fontSize = 16.sp, maxLines = 2) }
        if (state.error != null) CinemaButton(onClick = state::refresh, enabled = enabled) {
            Text("Try again", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

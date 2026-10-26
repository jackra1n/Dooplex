package com.jackra1n.dooplex

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors
import kotlinx.coroutines.launch

/**
 * Parent-owned focus intent outlives route composition and persistence work. Tab confirmation never
 * asks a destination to restore a poster; only Down explicitly hands focus to that route's header.
 * Ordinary child/details returns keep their existing content restoration. Passive fallback cannot
 * replace navigation/header ownership; horizontal key intent explicitly moves between tab targets.
 */
@Stable
internal class CollectionFocusState {
    val homeTab = FocusRequester()
    val movieTab = FocusRequester()
    val showTab = FocusRequester()
    var navigationType by mutableStateOf<String?>(null)
        private set
    var navigationEpoch by mutableIntStateOf(0)
        private set
    var headerRoute by mutableStateOf<String?>(null)
        private set
    var headerEpoch by mutableIntStateOf(0)
        private set
    var contentType by mutableStateOf<String?>(null)
        private set
    var contentEpoch by mutableIntStateOf(0)
        private set

    fun tab(type: String): FocusRequester = when (type) {
        "home" -> homeTab
        "movie" -> movieTab
        else -> showTab
    }

    fun focusTab(type: String) {
        if (headerRoute != null) return
        if (navigationType == null) navigationType = type
        else if (navigationType != type) navigationEpoch++
    }

    fun moveTab(type: String) {
        navigationType = type
        headerRoute = null
        navigationEpoch++
    }

    /** A confirmed tab drops focus into its page (plx-native): the hero on Home, the first poster elsewhere. */
    fun enterContent(type: String) {
        navigationType = null
        headerRoute = null
        contentType = type
        contentEpoch++
    }

    fun completeContent(type: String) {
        if (contentType != type) return
        contentType = null
        Analytics.event("ready", "what" to type)
    }

    fun enterHeader(route: String) {
        navigationType = null
        headerRoute = route
        headerEpoch++
    }

    fun completeHeader(route: String) {
        if (headerRoute == route) headerRoute = null
    }
}

/**
 * Widens a padded container's child back to the full screen, so the top bar placed in a grid header
 * lines up with the one on Home. [start] and [end] are the container's insets from the screen edges.
 */
internal fun Modifier.bleed(start: Dp, end: Dp) = layout { measurable, constraints ->
    val width = constraints.maxWidth + (start + end).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}

/**
 * A centred glass track: the current route keeps a translucent plate, focus slides a light pill over it.
 * Stable parent-owned requesters reattach when lazy headers scroll or destinations change.
 * D-pad movement only moves focus; Center/click confirms a route, and Down enters its header.
 * Sizes follow plx-native's 1920-wide layout scaled to this screen; capsules animate draw-only.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionNavigationBar(
    accountName: String,
    activeType: String,
    movieTitle: String?,
    showTitle: String?,
    focus: CollectionFocusState,
    enabled: Boolean,
    onHome: () -> Unit,
    onMovies: () -> Unit,
    onShows: () -> Unit,
    onDown: () -> Unit,
) {
    val tabs = remember(movieTitle, showTitle) {
        buildList {
            add("home" to "Home")
            if (movieTitle != null) add("movie" to movieTitle)
            if (showTitle != null) add("show" to showTitle)
        }
    }
    var focusedType by remember { mutableStateOf<String?>(null) }
    val visible = LocalScreenVisible.current
    LaunchedEffect(activeType, focus.navigationEpoch, visible) {
        if (visible) focus.navigationType?.let { focus.tab(it).requestFocus() }
    }
    val density = LocalDensity.current
    val unit = LocalConfiguration.current.screenWidthDp.dp / 1920f
    val unitPx = with(density) { unit.toPx() }
    val pillHeight = 60 * unitPx
    val pad = 8 * unitPx
    val gap = 16 * unitPx
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(fontSize = with(density) { (unit * 28).toSp() })
    val tabWidths = remember(tabs, textMeasurer, labelStyle, unitPx) {
        val padding = 36 * unitPx
        val maximum = 420 * unitPx
        FloatArray(tabs.size) { index ->
            // Bold is the widest face, so a pill never resizes when its label changes weight.
            (textMeasurer.measure(
                text = tabs[index].second, style = labelStyle.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (maximum - padding).toInt().coerceAtLeast(0)),
            ).size.width + padding).coerceAtMost(maximum)
        }
    }
    val tabOffsets = remember(tabWidths, gap) {
        var offset = 0f
        FloatArray(tabWidths.size) { index -> offset.also { offset += tabWidths[index] + gap } }
    }
    val activeIndex = tabs.indexOfFirst { it.first == activeType }
    val focusIndex = tabs.indexOfFirst { it.first == focusedType }
    val selection = rememberCapsule(activeIndex, tabOffsets, tabWidths)
    val highlight = rememberCapsule(focusIndex, tabOffsets, tabWidths)
    // plx-native's safe area: 5% of the screen at the sides, so the bar is identical on every page.
    Row(
        Modifier.fillMaxWidth().padding(start = unit * 96, end = unit * 96, top = unit * 54, bottom = unit * 40),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            AccountChip(accountName, unit, labelStyle)
        }
        if (tabs.isNotEmpty()) Row(
            Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .glassTrack()
                .drawWithCache {
                    val pill = CornerRadius(pillHeight / 2)
                    val plateRim = litRim(pad, pillHeight, pillHeight / 2)
                    onDrawBehind {
                        val plate = selection.alpha.value
                        if (plate > 0f) {
                            val topLeft = Offset(pad + selection.x.value, pad)
                            val plateSize = Size(selection.width.value, pillHeight)
                            drawRoundRect(Glass.Plate, topLeft, plateSize, pill, alpha = plate)
                            drawRim(plateRim, topLeft, plateSize, pillHeight / 2, plate)
                        }
                        val light = highlight.alpha.value
                        if (light > 0f) drawRoundRect(
                            Glass.Light, Offset(pad + highlight.x.value, pad),
                            Size(highlight.width.value, pillHeight), pill, alpha = light,
                        )
                    }
                }
                .padding(with(density) { pad.toDp() })
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() }),
        ) {
            tabs.forEachIndexed { index, (type, label) ->
                val isActive = index == activeIndex
                val style = labelStyle.copy(
                    fontWeight = if (isActive || index == focusIndex) FontWeight.Bold else FontWeight.Normal,
                )
                Box(
                    Modifier.width(with(density) { tabWidths[index].toDp() })
                        .height(with(density) { pillHeight.toDp() })
                        .then(if (visible) Modifier.focusRequester(focus.tab(type)) else Modifier)
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (enabled && event.type == KeyEventType.KeyDown) onDown()
                                    true
                                }
                                Key.DirectionUp -> true
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    if (enabled && event.type == KeyEventType.KeyDown) {
                                        val current = tabs.indexOfFirst { it.first == focus.navigationType }
                                            .takeIf { it >= 0 } ?: index
                                        val next = (current + if (event.key == Key.DirectionRight) 1 else -1)
                                            .coerceIn(0, tabs.lastIndex)
                                        focus.moveTab(tabs[next].first)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }.onFocusChanged {
                            if (it.isFocused) {
                                focusedType = type
                                focus.focusTab(type)
                            } else if (focusedType == type) focusedType = null
                        }
                        .semantics { selected = activeType == type }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Tab,
                            onClick = {
                                if (enabled) {
                                    focus.enterContent(type)
                                    when (type) {
                                        "home" -> onHome()
                                        "movie" -> onMovies()
                                        else -> onShows()
                                    }
                                }
                            },
                        ).padding(horizontal = unit * 18),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (isActive) Glass.Light else Glass.IdleInk,
                        style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Dark ink only where the light pill covers the glyphs, so a label can split mid-slide.
                    Text(label, color = Glass.Ink, style = style,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
                            if (highlight.alpha.value > INK_FLIP_ALPHA) {
                                val left = highlight.x.value - tabOffsets[index] - (tabWidths[index] - size.width) / 2f
                                val clipLeft = left.coerceAtLeast(0f)
                                val clipRight = (left + highlight.width.value).coerceAtMost(size.width)
                                if (clipRight > clipLeft) clipRect(left = clipLeft, right = clipRight) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        })
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private const val INK_FLIP_ALPHA = 0.15f
private val CapsuleMove = spring<Float>(dampingRatio = 1f, stiffness = 240f)
private val CapsuleFade = spring<Float>(dampingRatio = 1f, stiffness = 300f)

private class Capsule(x: Float, width: Float, alpha: Float) {
    val x = Animatable(x)
    val width = Animatable(width)
    val alpha = Animatable(alpha)
}

/** Glides between tabs while visible; a capsule that is fading in starts at its target instead. */
@Composable
private fun rememberCapsule(index: Int, offsets: FloatArray, widths: FloatArray): Capsule {
    val capsule = remember {
        Capsule(offsets.getOrElse(index) { 0f }, widths.getOrElse(index) { 0f }, if (index >= 0) 1f else 0f)
    }
    LaunchedEffect(index, offsets, widths) {
        if (index !in offsets.indices) {
            capsule.alpha.animateTo(0f, CapsuleFade)
            return@LaunchedEffect
        }
        if (capsule.alpha.value < 0.02f) {
            capsule.x.snapTo(offsets[index])
            capsule.width.snapTo(widths[index])
        }
        launch { capsule.x.animateTo(offsets[index], CapsuleMove) }
        launch { capsule.width.animateTo(widths[index], CapsuleMove) }
        capsule.alpha.animateTo(1f, CapsuleFade)
    }
    return capsule
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountChip(accountName: String, unit: Dp, labelStyle: TextStyle) {
    val initial = accountName.trim().take(1).uppercase()
    Box(
        Modifier.size(unit * 76).glassTrack().semantics { contentDescription = "Signed in as $accountName" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(unit * 60).background(Glass.Plate, CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, color = Glass.Light, style = labelStyle.copy(fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** A single library stays a compact label; multiple same-type sources expose an ID-keyed chooser. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionLibraryControl(
    library: PlexLibrary,
    libraries: List<PlexLibrary>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChoose: (PlexLibrary) -> Unit,
    onDismiss: () -> Unit,
) {
    if (libraries.size > 1) {
        var open by remember(library.key) { mutableStateOf(false) }
        CinemaButton(onClick = { open = true }, enabled = enabled, modifier = modifier.widthIn(max = 300.dp)) {
            Text("${library.title}  ⌄", fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "Library: ${library.title}. Choose another library." })
        }
        if (open) CollectionLibraryDialog(
            libraries = libraries,
            selectedKey = library.key,
            onDismiss = { open = false; onDismiss() },
            onChoose = { open = false; onChoose(it) },
        )
    } else {
        Text(library.title, color = CinemaColors.Foreground, fontSize = 18.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = modifier.padding(horizontal = 8.dp))
    }
}

/** Duplicate titles remain distinguishable, while selection and persistence always use library IDs. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionLibraryDialog(
    libraries: List<PlexLibrary>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onChoose: (PlexLibrary) -> Unit,
) {
    val initial = remember { FocusRequester() }
    val initialKey = selectedKey?.takeIf { key -> libraries.any { it.key == key } } ?: libraries.firstOrNull()?.key
    val duplicateTitles = remember(libraries) { libraries.groupingBy { it.title }.eachCount().filterValues { it > 1 }.keys }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(top = 80.dp).widthIn(max = 520.dp).fillMaxWidth()
                    .background(CinemaColors.Canvas, RoundedCornerShape(22.dp)).glassRim(22.dp)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Choose a library", color = CinemaColors.Foreground, fontSize = 22.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
                libraries.forEach { library ->
                    CinemaButton(
                        onClick = { onChoose(library) }, selected = selectedKey == library.key,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (library.key == initialKey) Modifier.focusRequester(initial) else Modifier),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(library.title, fontSize = 17.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (library.title in duplicateTitles) Text("Library ${library.key}",
                                fontSize = 13.sp)
                        }
                        if (library.key == selectedKey) Text("Selected", fontSize = 12.sp)
                    }
                }
                CinemaButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", fontSize = 16.sp)
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            if (initialKey != null) initial.requestFocus()
        }
    }
}

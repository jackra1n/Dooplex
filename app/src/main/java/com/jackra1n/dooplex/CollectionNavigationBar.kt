package com.jackra1n.dooplex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/**
 * Parent-owned focus intent outlives route composition and persistence work. Tab confirmation never
 * asks a destination to restore a poster; only Down explicitly hands focus to that route's header.
 * Ordinary child/details returns keep their existing content restoration. Passive fallback cannot
 * replace navigation/header ownership; horizontal key intent explicitly moves between tab targets.
 */
@Stable
internal class CollectionFocusState {
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

    fun confirmTab(type: String) {
        moveTab(type)
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
 * One quiet collection track: the route marker persists independently of the bright focus pill.
 * Stable parent-owned requesters reattach when lazy headers scroll or destinations change.
 * D-pad movement only moves focus; Center/click confirms a route, and Down enters its header.
 * Labels set tab widths within per-tab/track caps; highlight position and width animate draw-only.
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
    onMovies: () -> Unit,
    onShows: () -> Unit,
    onDown: () -> Unit,
) {
    val tabs = remember(movieTitle, showTitle) {
        buildList {
            if (movieTitle != null) add("movie" to movieTitle)
            if (showTitle != null) add("show" to showTitle)
        }
    }
    var focusedType by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeType, focus.navigationEpoch) {
        when (focus.navigationType) {
            "movie" -> focus.movieTab.requestFocus()
            "show" -> focus.showTab.requestFocus()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 720.dp
        val showPeripheral = maxWidth >= 480.dp
        val availableTrack = maxWidth * if (showPeripheral) 0.62f else 1f
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = LocalTextStyle.current.copy(
            fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium,
        )
        val maxTabWidth = 320.dp.coerceAtMost(
            ((availableTrack - 8.dp) / tabs.size.coerceAtLeast(1)).coerceAtLeast(0.dp),
        )
        val tabWidths = remember(tabs, textMeasurer, labelStyle, density, maxTabWidth) {
            with(density) {
                val padding = 24.dp.roundToPx()
                val maximum = maxTabWidth.roundToPx()
                FloatArray(tabs.size) { index ->
                    (textMeasurer.measure(
                        text = tabs[index].second, style = labelStyle,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (maximum - padding).coerceAtLeast(0)),
                    ).size.width + padding).coerceAtMost(maximum).toFloat()
                }
            }
        }
        val tabOffsets = remember(tabWidths) {
            var offset = 0f
            FloatArray(tabWidths.size) { index -> offset.also { offset += tabWidths[index] } }
        }
        val activeIndex = tabs.indexOfFirst { it.first == activeType }
        val focusIndex = tabs.indexOfFirst { it.first == focusedType }
        val targetIndex = focusIndex.takeIf { it >= 0 } ?: activeIndex.coerceAtLeast(0)
        val focusOffset = animateFloatAsState(
            targetValue = tabOffsets.getOrElse(targetIndex) { 0f },
            animationSpec = tween(130), label = "collection focus position",
        )
        val focusWidth = animateFloatAsState(
            targetValue = tabWidths.getOrElse(targetIndex) { 0f },
            animationSpec = tween(130), label = "collection focus width",
        )
        val showFocus = focusIndex >= 0
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (showPeripheral) Text("DOOPLEX", color = CinemaColors.Accent,
                    fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
            if (tabs.isNotEmpty()) {
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                        .background(CinemaControlColors.Surface, shape)
                        .border(0.5.dp, CinemaControlColors.Outline, shape)
                        .padding(4.dp)
                        .drawWithCache {
                            val radius = CornerRadius(size.height / 2f)
                            onDrawBehind {
                                if (activeIndex >= 0) drawRoundRect(
                                    CinemaControlColors.Active,
                                    topLeft = Offset(tabOffsets[activeIndex], 0f),
                                    size = Size(tabWidths[activeIndex], size.height),
                                    cornerRadius = radius,
                                )
                                if (showFocus) drawRoundRect(
                                    CinemaControlColors.Focus, topLeft = Offset(focusOffset.value, 0f),
                                    size = Size(focusWidth.value, size.height), cornerRadius = radius,
                                )
                            }
                        }.focusGroup(),
                ) {
                    tabs.forEachIndexed { index, (type, label) ->
                        Box(
                            Modifier.width(with(density) { tabWidths[index].toDp() }).height(46.dp)
                                .focusRequester(if (type == "movie") focus.movieTab else focus.showTab)
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
                                            focus.confirmTab(type)
                                            if (type == "movie") onMovies() else onShows()
                                        }
                                    },
                                ).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = CinemaColors.Foreground,
                                style = labelStyle,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Invert only the glyphs actually under the moving light pill. Both the
                            // departing and arriving labels retain contrast throughout translation.
                            Text(label, color = CinemaColors.Canvas,
                                style = labelStyle,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
                                    if (showFocus) {
                                        val left = focusOffset.value - tabOffsets[index] - (tabWidths[index] - size.width) / 2f
                                        val clipLeft = left.coerceAtLeast(0f)
                                        val clipRight = (left + focusWidth.value).coerceAtMost(size.width)
                                        if (clipRight > clipLeft) clipRect(left = clipLeft, right = clipRight) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                })
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).padding(start = if (showPeripheral) 12.dp else 0.dp),
                contentAlignment = Alignment.CenterEnd) {
                if (showPeripheral) Text(accountName, color = CinemaColors.Muted,
                    fontSize = if (compact) 13.sp else 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
                    .background(CinemaColors.Canvas, RoundedCornerShape(22.dp)).padding(24.dp)
                    .verticalScroll(rememberScrollState()),
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

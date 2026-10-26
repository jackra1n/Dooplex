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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/**
 * One quiet collection track: the grey route marker persists independently of the bright focus pill.
 * D-pad movement only moves focus; Center/click confirms a route. Translation is draw-only, without
 * animated measurement or recomposition. A single available source keeps the same tab behavior.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionNavigationBar(
    accountName: String,
    activeType: String,
    hasMovies: Boolean,
    hasShows: Boolean,
    enabled: Boolean,
    onMovies: () -> Unit,
    onShows: () -> Unit,
) {
    val tabs = remember(hasMovies, hasShows) {
        buildList {
            if (hasMovies) add("movie" to "Movies")
            if (hasShows) add("show" to "Shows")
        }
    }
    var focusedType by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 720.dp
        val showPeripheral = maxWidth >= 480.dp
        val tabWidth = (if (compact) 92.dp else 112.dp).coerceAtMost(
            ((maxWidth - 8.dp) / tabs.size.coerceAtLeast(1)).coerceAtLeast(0.dp),
        )
        val tabWidthPx = with(LocalDensity.current) { tabWidth.toPx() }
        val activeIndex = tabs.indexOfFirst { it.first == activeType }
        val focusIndex = tabs.indexOfFirst { it.first == focusedType }
        val focusOffset = animateFloatAsState(
            targetValue = (focusIndex.takeIf { it >= 0 } ?: activeIndex.coerceAtLeast(0)) * tabWidthPx,
            animationSpec = tween(130), label = "collection focus position",
        )
        val showFocus = enabled && focusIndex >= 0
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
                        .background(CinemaColors.Foreground.copy(alpha = 0.035f), shape)
                        .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.07f), shape)
                        .padding(4.dp)
                        .drawWithCache {
                            val pillSize = Size(tabWidthPx, size.height)
                            val radius = CornerRadius(size.height / 2f)
                            onDrawBehind {
                                if (activeIndex >= 0) drawRoundRect(
                                    CinemaColors.Foreground.copy(alpha = 0.13f),
                                    topLeft = Offset(activeIndex * tabWidthPx, 0f), size = pillSize,
                                    cornerRadius = radius,
                                )
                                if (showFocus) drawRoundRect(
                                    CinemaColors.Foreground, topLeft = Offset(focusOffset.value, 0f),
                                    size = pillSize, cornerRadius = radius,
                                )
                            }
                        }.focusGroup(),
                ) {
                    tabs.forEachIndexed { index, (type, label) ->
                        Box(
                            Modifier.width(tabWidth).height(46.dp)
                                .onFocusChanged {
                                    if (it.isFocused) focusedType = type
                                    else if (focusedType == type) focusedType = null
                                }
                                .semantics { selected = activeType == type }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null, enabled = enabled, role = Role.Tab,
                                    onClick = if (type == "movie") onMovies else onShows,
                                ).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = CinemaColors.Foreground,
                                fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Invert only the glyphs actually under the moving light pill. Both the
                            // departing and arriving labels retain contrast throughout translation.
                            Text(label, color = CinemaColors.Canvas,
                                fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
                                    if (showFocus) {
                                        val left = focusOffset.value - index * tabWidthPx - (tabWidthPx - size.width) / 2f
                                        val clipLeft = left.coerceAtLeast(0f)
                                        val clipRight = (left + tabWidthPx).coerceAtMost(size.width)
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

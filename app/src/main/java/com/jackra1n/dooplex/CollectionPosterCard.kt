package com.jackra1n.dooplex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** Movies, shows and seasons share the same fixed geometry and compositor-only focus treatment. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionPosterCard(
    item: PlexBrowseItem?,
    posters: PosterRepository,
    artworkWidth: Int,
    loadArtwork: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    placeholderLabel: String,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val scale = animateFloatAsState(if (focused) 1.035f else 1f, tween(160), label = "poster focus")
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "poster highlight")
    Column(
        modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick,
        ).semantics {
            contentDescription = if (item == null) "$placeholderLabel, loading. Select to load this item."
                else listOfNotNull(item.title, item.subtitle.takeIf { it.isNotBlank() },
                    if (item.watched) "Watched" else if (item.viewOffsetMs > 0) "Partially watched" else null).joinToString(", ")
        }.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)
            .background(CinemaColors.Panel)
            .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.07f), shape)
            .drawWithCache {
                val highlight = Brush.radialGradient(
                    colors = listOf(CinemaColors.Foreground.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.5f, 0f), radius = size.height.coerceAtLeast(1f),
                )
                val shine = Brush.linearGradient(
                    0f to CinemaColors.Foreground.copy(alpha = 0.72f),
                    0.22f to CinemaColors.Foreground.copy(alpha = 0.28f),
                    0.58f to CinemaColors.Foreground.copy(alpha = 0.07f),
                    1f to CinemaColors.Foreground.copy(alpha = 0.20f),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                val edge = Stroke(1.dp.toPx())
                val halo = Stroke(3.dp.toPx())
                val inset = edge.width / 2
                val outlineSize = Size((size.width - edge.width).coerceAtLeast(0f), (size.height - edge.width).coerceAtLeast(0f))
                val corner = CornerRadius((9.dp.toPx() - inset).coerceAtLeast(0f))
                onDrawWithContent {
                    drawContent()
                    if (light.value > 0f) {
                        drawRect(highlight, alpha = light.value)
                        drawRoundRect(shine, Offset(inset, inset), outlineSize, corner, alpha = light.value * 0.14f, style = halo)
                        drawRoundRect(shine, Offset(inset, inset), outlineSize, corner, alpha = light.value, style = edge)
                    }
                }
            }) {
            PosterArtwork(item?.thumbPath, posters, artworkWidth, loadArtwork,
                Modifier.fillMaxSize(), item?.title ?: placeholderLabel, metadataLoading = item == null)
            if (item?.watched == true) {
                Text("WATCHED", color = CinemaColors.Foreground, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .clip(RoundedCornerShape(5.dp)).background(CinemaColors.Canvas.copy(alpha = 0.88f))
                        .padding(horizontal = 7.dp, vertical = 5.dp))
            }
            if (item != null && item.viewOffsetMs > 0 && item.durationMs > 0 && !item.watched) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(CinemaColors.Canvas.copy(alpha = 0.8f))) {
                    Box(Modifier.fillMaxWidth((item.viewOffsetMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().background(CinemaColors.Accent))
                }
            }
        }
        Text(item?.title ?: "Loading…", color = if (focused) CinemaColors.Foreground else CinemaColors.Foreground.copy(alpha = 0.85f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item?.subtitle?.ifBlank { " " } ?: " ", color = CinemaColors.Muted, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

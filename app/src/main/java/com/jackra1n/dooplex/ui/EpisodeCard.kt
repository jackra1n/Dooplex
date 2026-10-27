package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.artwork.ArtworkImage
import com.jackra1n.dooplex.artwork.PosterRepository
import com.jackra1n.dooplex.artwork.rememberArtworkReveal
import com.jackra1n.dooplex.artwork.rememberMovieArtworkPainter
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.ui.theme.CinemaColors

@Composable
internal fun EpisodeCard(
    item: PlexBrowseItem?,
    posters: PosterRepository,
    artworkWidth: Int,
    loadArtwork: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    placeholderLabel: String,
    label: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(180), label = "episode focus")
    val light = animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "episode highlight")
    val painter = rememberMovieArtworkPainter(item?.thumbPath, posters, artworkWidth, artworkWidth * 9 / 16, loadArtwork)
    val reveal = rememberArtworkReveal(item?.thumbPath, artworkWidth, painter != null)
    val status = remember(item) {
        if (item == null) " " else buildList {
            if (item.durationMs > 0) add("${(item.durationMs / 60000).coerceAtLeast(1)} min")
            if (item.watched) add("Watched")
            else if (item.viewOffsetMs > 0) add("Resume at ${episodePosition(item.viewOffsetMs)}")
        }.joinToString(" · ").ifBlank { " " }
    }
    Column(
        modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }.cardClick(item, enabled, onClick)
            .semantics { contentDescription = if (item == null) "$placeholderLabel, loading. Select to retry loading."
                else listOf(item.title, item.subtitle, status).filter { it.isNotBlank() }.joinToString(", ") }
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(CinemaColors.Panel)
            .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.07f), shape)
            .focusLight(light, 9.dp), contentAlignment = Alignment.Center) {
            if (painter != null) ArtworkImage(painter, reveal, Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Text((item?.title ?: placeholderLabel).take(1).uppercase(), color = CinemaColors.Accent.copy(alpha = 0.5f), fontSize = 32.sp)
                if (item != null && item.thumbPath == null) Text("NO ARTWORK", color = CinemaColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            if (item?.watched == true) Text("WATCHED", color = CinemaColors.Foreground, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(5.dp))
                    .background(CinemaColors.Canvas.copy(alpha = 0.88f)).padding(horizontal = 7.dp, vertical = 5.dp))
            if (item != null && item.viewOffsetMs > 0 && item.durationMs > 0 && !item.watched) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(CinemaColors.Canvas.copy(alpha = 0.8f))) {
                    Box(Modifier.fillMaxWidth((item.viewOffsetMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().background(CinemaColors.Accent))
                }
            }
        }
        Text(item?.title ?: "Loading…", color = CinemaColors.Foreground.copy(alpha = if (focused) 1f else 0.7f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text((label ?: item?.subtitle)?.ifBlank { " " } ?: " ", color = CinemaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = if (item?.viewOffsetMs != null && item.viewOffsetMs > 0 && !item.watched) CinemaColors.Accent else CinemaColors.Muted,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun episodePosition(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    return "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}

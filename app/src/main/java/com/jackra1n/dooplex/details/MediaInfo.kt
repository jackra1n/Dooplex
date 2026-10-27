package com.jackra1n.dooplex.details

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jackra1n.dooplex.plex.MediaInfoSection
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.ui.Glass
import com.jackra1n.dooplex.ui.Text
import com.jackra1n.dooplex.ui.glassRim
import com.jackra1n.dooplex.ui.theme.CinemaColors
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MediaInfoState(val item: PlexBrowseItem, client: PlexClient, scope: CoroutineScope) {
    var sections by mutableStateOf<List<MediaInfoSection>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val job = scope.launch {
        try {
            sections = client.mediaInfo(item.ratingKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            error = failure.message ?: "Plex could not load the media info."
        }
    }

    fun close() = job.cancel()
}

/** The title's files and streams in a panel; Up and Down scroll, Back closes. */
@Composable
internal fun MediaInfoDialog(state: MediaInfoState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.requestFocus()
        }
        Column(
            Modifier.width(900.dp).fillMaxHeight(0.86f)
                .background(Glass.Panel, RoundedCornerShape(26.dp)).glassRim(26.dp)
                .padding(horizontal = 44.dp, vertical = 32.dp),
        ) {
            Text("MEDIA INFO", color = CinemaColors.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp)
            Text(state.item.title, color = CinemaColors.Foreground, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().focusRequester(focus).focusable()
                    .onPreviewKeyEvent {
                        val step = when (it.key) {
                            Key.DirectionDown -> SCROLL_STEP
                            Key.DirectionUp -> -SCROLL_STEP
                            Key.DirectionLeft, Key.DirectionRight -> 0f
                            else -> return@onPreviewKeyEvent false
                        }
                        if (it.type == KeyEventType.KeyDown && step != 0f) scope.launch { scroll.animateScrollBy(step) }
                        true
                    }
                    .verticalScroll(scroll),
            ) {
                val sections = state.sections
                Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    when {
                        state.error != null -> Note(state.error.orEmpty())
                        sections == null -> Note("Loading…")
                        sections.isEmpty() -> Note("Plex has no file information for this title.")
                        else -> sections.forEach { Section(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(section: MediaInfoSection) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(section.title.uppercase(), color = CinemaColors.Muted, fontSize = 14.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
        section.rows.forEach { (label, value) ->
            Row {
                Text(label, color = CinemaColors.Muted, fontSize = 17.sp, modifier = Modifier.width(190.dp))
                Text(value, color = CinemaColors.Foreground, fontSize = 17.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = CinemaColors.Muted, fontSize = 17.sp)
}

private const val SCROLL_STEP = 260f

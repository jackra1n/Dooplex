package com.jackra1n.dooplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jackra1n.dooplex.ui.theme.CinemaColors
import kotlinx.coroutines.delay

/** A short message over a page that has no place of its own for errors; it clears itself. */
@Composable
internal fun MessageToast(message: String, onDone: () -> Unit) {
    LaunchedEffect(message) {
        delay(TOAST_MS)
        onDone()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            color = CinemaColors.Foreground, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 720.dp)
                .background(Glass.Panel, RoundedCornerShape(24.dp)).glassRim(24.dp)
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private const val TOAST_MS = 6_000L

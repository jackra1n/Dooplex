package com.jackra1n.dooplex.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun DooplexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CinemaColors.Accent,
            onPrimary = CinemaColors.OnAccent,
            background = CinemaColors.Canvas,
            onBackground = CinemaColors.Foreground,
            surface = CinemaColors.Canvas,
            onSurface = CinemaColors.Foreground,
            surfaceVariant = CinemaColors.Panel,
            onSurfaceVariant = CinemaColors.Muted,
            error = CinemaColors.Error,
            onError = CinemaColors.OnError,
        ),
        typography = Typography,
    ) {
        // Not tv-material's Surface: from 1.1 it draws a full-screen glow pass under the whole app on
        // every frame, even with no glow, which cost the library grid about 1.4% of its frames.
        CompositionLocalProvider(LocalContentColor provides CinemaColors.Foreground) {
            Box(Modifier.fillMaxSize().background(CinemaColors.Canvas)) { content() }
        }
    }
}
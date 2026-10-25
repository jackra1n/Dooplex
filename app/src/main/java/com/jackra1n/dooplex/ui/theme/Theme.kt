package com.jackra1n.dooplex.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
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
        Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
            content()
        }
    }
}
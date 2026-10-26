package com.jackra1n.dooplex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import com.jackra1n.dooplex.ui.theme.CinemaColors

internal object CinemaControlColors {
    val Surface = Color.Black.copy(alpha = 0.45f)
    val Outline = CinemaColors.Foreground.copy(alpha = 0.16f)
    val Active = CinemaColors.Foreground.copy(alpha = 0.13f)
    val Focus = CinemaColors.Foreground
}

/** One flat-glass treatment: light primary/focus, dark secondary, no painted grey sheen. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CinemaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val light = focused || primary
    val contentColor = if (light) Color.Black else CinemaColors.Foreground
    val surface = if (light) CinemaControlColors.Focus else CinemaControlColors.Surface
    val shape = RoundedCornerShape(50)
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.025f else 1f,
        animationSpec = tween(160),
        label = "control focus",
    )
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = if (enabled) 1f else 0.45f
                }
                .clip(shape)
                .drawWithCache {
                    onDrawBehind {
                        drawRect(surface)
                        if (selected && !light) drawRect(CinemaControlColors.Active)
                    }
                }
                .border(0.5.dp, if (light) CinemaControlColors.Focus else CinemaControlColors.Outline, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .defaultMinSize(minHeight = 46.dp)
                .padding(PaddingValues(horizontal = 20.dp, vertical = 11.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content,
        )
    }
}

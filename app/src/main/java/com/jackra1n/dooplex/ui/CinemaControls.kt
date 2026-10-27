package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** Dark glass at rest; focus lifts a light face with a soft shadow, primary actions stay light. */
@Composable
internal fun CinemaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    primary: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val light = focused || primary
    val shape = RoundedCornerShape(50)
    val lift = animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 320f), label = "control focus")
    val face = animateFloatAsState(if (light) 1f else 0f, spring(dampingRatio = 1f, stiffness = 320f), label = "control face")
    CompositionLocalProvider(LocalContentColor provides if (light) Glass.Ink else CinemaColors.Foreground) {
        Row(
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .graphicsLayer {
                    val scale = 1f + 0.07f * lift.value
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.45f
                    shadowElevation = lift.value * 10.dp.toPx()
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                    this.shape = shape
                    clip = true
                }
                .drawWithCache {
                    val radius = size.height / 2
                    val idle = Brush.verticalGradient(listOf(Glass.ControlTop, Glass.ControlBottom))
                    val lit = Brush.verticalGradient(listOf(Glass.LightTop, Glass.LightBottom))
                    val rim = litRim(0f, size.height, radius, Glass.ControlRim)
                    onDrawBehind {
                        val amount = face.value
                        if (amount < 1f) {
                            drawRect(idle)
                            if (selected) drawRect(Glass.Plate)
                            drawRim(rim, Offset.Zero, size, radius, 1f - amount)
                        }
                        if (amount > 0f) drawRect(lit, alpha = amount)
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .defaultMinSize(minHeight = 46.dp)
                .padding(padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

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
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** A dark, remote-friendly control with a crisp focus ring and stable layout geometry. */
@Composable
internal fun CinemaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    // Read animated values only in draw/layer lambdas, not in composition or measurement.
    val fill = animateFloatAsState(
        targetValue = when {
            focused -> 0.94f
            selected -> 0.84f
            else -> 0.68f
        },
        animationSpec = tween(140),
        label = "control fill",
    )
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.025f else 1f,
        animationSpec = tween(160),
        label = "control focus",
    )
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
                val surface = Color.Black
                onDrawBehind {
                    drawRect(surface, alpha = fill.value)
                }
            }
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = CinemaColors.Foreground.copy(alpha = when {
                    focused -> 0.95f
                    selected -> 0.42f
                    else -> 0.18f
                }),
                shape = shape,
            )
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

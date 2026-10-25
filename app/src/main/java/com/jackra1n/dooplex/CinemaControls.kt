package com.jackra1n.dooplex

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** A fixed-size, remote-friendly glass control. Focus never changes its layout geometry. */
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
    val fill = when {
        focused -> CinemaColors.Accent.copy(alpha = 0.26f)
        selected -> CinemaColors.Accent.copy(alpha = 0.14f)
        else -> CinemaColors.Panel.copy(alpha = 0.80f)
    }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.68f))))
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> CinemaColors.Accent
                    selected -> CinemaColors.Accent.copy(alpha = 0.58f)
                    else -> CinemaColors.Foreground.copy(alpha = 0.13f)
                },
                shape,
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

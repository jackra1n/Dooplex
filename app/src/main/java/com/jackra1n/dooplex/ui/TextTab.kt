package com.jackra1n.dooplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** A quiet header control: plain text at rest, a plate when selected, the light pill when focused or [lit]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TextTab(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    lit: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val light = focused || lit
    val ink = when {
        light -> Glass.Ink
        selected -> CinemaColors.Foreground
        else -> Glass.IdleInk
    }
    Box(
        modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(50))
            .background(if (light) Glass.Light else if (selected) Glass.Plate else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, role = Role.Tab, onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, color = ink, fontSize = 17.sp,
            fontWeight = if (selected || light) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

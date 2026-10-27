package com.jackra1n.dooplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** A small glass panel dropped below its anchor; it holds focus until Back or a choice closes it. */
@Composable
internal fun GlassMenu(
    alignment: Alignment,
    offset: IntOffset,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Popup(alignment = alignment, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(width).background(Glass.Panel, RoundedCornerShape(26.dp)).glassRim(26.dp).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
internal fun MenuLabel(text: String) {
    Text(text.uppercase(), color = CinemaColors.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
internal fun MenuDivider() {
    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Glass.Rim))
}

/**
 * A menu choice: plain text at rest, the light pill when focused. [checked] rows show a check in a
 * column every row of a [checkable] menu keeps, so labels stay aligned; [trailing] is right-aligned.
 */
@Composable
internal fun MenuRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String = "",
    trailing: String = "",
    checkable: Boolean = false,
    checked: Boolean = false,
    more: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Glass.Ink else CinemaColors.Foreground
    Row(
        modifier.fillMaxWidth().height(if (detail.isBlank()) 52.dp else 62.dp).clip(RoundedCornerShape(50))
            .background(if (focused) Glass.Light else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = onClick,
            )
            .semantics { if (checkable) selected = checked }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkable) Text(if (checked) "✓" else "", color = ink, fontSize = 19.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = ink.copy(alpha = 0.65f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotBlank()) Text(trailing, color = ink.copy(alpha = if (focused) 1f else 0.7f), fontSize = 17.sp)
        if (more) Text("›", color = ink, fontSize = 26.sp)
    }
}

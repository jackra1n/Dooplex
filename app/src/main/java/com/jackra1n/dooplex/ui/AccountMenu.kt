package com.jackra1n.dooplex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** The account bubble at the left of the top bar; selecting it opens the account menu below it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AccountChip(
    accountName: String,
    avatar: ImageBitmap?,
    serverName: String,
    unit: Dp,
    labelStyle: TextStyle,
    enabled: Boolean,
    onDown: () -> Unit,
    onSwitchServer: () -> Unit,
    onSignOut: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val light by animateFloatAsState(if (focused || open) 1f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "account light")
    val initial = accountName.trim().take(1).uppercase()
    Box {
        Box(
            Modifier.size(unit * 76).glassTrack()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    if (enabled && event.type == KeyEventType.KeyDown) onDown()
                    true
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, onClick = { open = true },
                )
                .semantics { contentDescription = "Account, $accountName" },
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                // The picture fills the bubble; focus shows as a light ring around it.
                Box(Modifier.size(unit * 76).drawBehind { if (light > 0f) drawCircle(Glass.Light, alpha = light) })
                Image(avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(unit * 60).clip(CircleShape))
            } else Box(
                Modifier.size(unit * 60).drawBehind {
                    drawCircle(Glass.Plate)
                    if (light > 0f) drawCircle(Glass.Light, alpha = light)
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(initial, color = lerp(Glass.Light, Glass.Ink, light),
                    style = labelStyle.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
        if (open) {
            val below = with(LocalDensity.current) { (unit * 90).roundToPx() }
            AccountMenu(
                accountName = accountName,
                avatar = avatar,
                serverName = serverName,
                offset = IntOffset(0, below),
                onDismiss = { open = false },
                onSwitchServer = { open = false; onSwitchServer() },
                onSignOut = { open = false; onSignOut() },
            )
        }
    }
}

/** Signing out asks once more, because linking again means scanning the sign-in code. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountMenu(
    accountName: String,
    avatar: ImageBitmap?,
    serverName: String,
    offset: IntOffset,
    onDismiss: () -> Unit,
    onSwitchServer: () -> Unit,
    onSignOut: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(confirming) {
        withFrameNanos { }
        first.requestFocus()
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = { if (confirming) confirming = false else onDismiss() },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(320.dp).background(Glass.Panel, RoundedCornerShape(24.dp)).glassRim(24.dp)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("ACCOUNT", color = CinemaColors.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatar != null) Image(avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape))
                Column {
                    Text(accountName, color = CinemaColors.Foreground, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (serverName.isNotBlank()) Text(serverName, color = CinemaColors.Muted, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (confirming) {
                Text("Sign out of Plex? You'll need to scan the sign-in code again.",
                    color = CinemaColors.Muted, fontSize = 15.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                MenuRow("Cancel", Modifier.focusRequester(first)) { confirming = false }
                MenuRow("Sign out", onClick = onSignOut)
            } else {
                MenuRow("Switch server", Modifier.focusRequester(first), onClick = onSwitchServer)
                MenuRow("Sign out") { confirming = true }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MenuRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Glass.Ink else CinemaColors.Foreground
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50))
            .background(if (focused) Glass.Light else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text("›", color = ink, fontSize = 24.sp)
    }
}

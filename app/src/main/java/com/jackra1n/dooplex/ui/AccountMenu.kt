package com.jackra1n.dooplex.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/**
 * The account at the left of the top bar: a round picture at rest that grows into a pill with the
 * account name while focused or open. Selecting it opens the account menu below it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AccountChip(
    accountName: String,
    avatar: ImageBitmap?,
    serverName: String,
    unit: Dp,
    labelStyle: TextStyle,
    enabled: Boolean,
    focusModifier: Modifier,
    onDown: () -> Unit,
    onSwitchServer: () -> Unit,
    onSignOut: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val expanded = focused || open
    Box {
        Row(
            Modifier.height(unit * 76).glassTrack()
                .drawBehind { if (expanded) drawRoundRect(Glass.Light, cornerRadius = CornerRadius(size.height / 2)) }
                .then(focusModifier)
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
                .semantics { contentDescription = "Account, $accountName" }
                .animateContentSize(spring(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = IntSize.VisibilityThreshold))
                .padding(start = unit * 8, end = if (expanded) unit * 30 else unit * 8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountPicture(accountName, avatar, unit * 60, labelStyle)
            if (expanded) Text(
                accountName, color = Glass.Ink,
                style = labelStyle.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = unit * 18).widthIn(max = unit * 360),
            )
        }
        if (open) {
            val below = with(LocalDensity.current) { (unit * 92).roundToPx() }
            AccountMenu(
                serverName = serverName,
                offset = IntOffset(0, below),
                onDismiss = { open = false },
                onSwitchServer = { open = false; onSwitchServer() },
                onSignOut = { open = false; onSignOut() },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountPicture(accountName: String, avatar: ImageBitmap?, size: Dp, labelStyle: TextStyle) {
    if (avatar != null) {
        Image(avatar, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(Modifier.size(size).background(Glass.Plate, CircleShape), contentAlignment = Alignment.Center) {
            Text(accountName.trim().take(1).uppercase(), color = Glass.Light,
                style = labelStyle.copy(fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Signing out asks once more, because linking again means scanning the sign-in code. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountMenu(
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
    GlassMenu(Alignment.TopStart, offset, 320.dp, onDismiss = { if (confirming) confirming = false else onDismiss() }) {
        if (confirming) {
            Text("Sign out of Plex? You'll need to scan the sign-in code again.",
                color = CinemaColors.Muted, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            MenuRow("Cancel", Modifier.focusRequester(first)) { confirming = false }
            MenuRow("Sign out", onClick = onSignOut)
        } else {
            MenuRow("Switch server", Modifier.focusRequester(first), detail = serverName, more = true, onClick = onSwitchServer)
            MenuDivider()
            MenuRow("Sign out") { confirming = true }
        }
    }
}

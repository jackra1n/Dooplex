package com.jackra1n.dooplex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.CinemaColors

/** A shared collection switch; sources remain explicit and the page owns viewport/focus restoration. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CollectionNavigationBar(
    accountName: String,
    activeType: String,
    hasMovies: Boolean,
    hasShows: Boolean,
    enabled: Boolean,
    onMovies: () -> Unit,
    onShows: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 720.dp
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("DOOPLEX", color = CinemaColors.Accent, fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50))
                .background(CinemaColors.Foreground.copy(alpha = 0.035f))
                .border(0.5.dp, CinemaColors.Foreground.copy(alpha = 0.055f), RoundedCornerShape(50))
                .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasMovies) CinemaButton(onClick = onMovies, enabled = enabled, selected = activeType == "movie",
                    modifier = Modifier.semantics { role = Role.Tab; selected = activeType == "movie" }) {
                    Text("Movies", color = CinemaColors.Foreground, fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium)
                }
                if (hasShows) CinemaButton(onClick = onShows, enabled = enabled, selected = activeType == "show",
                    modifier = Modifier.semantics { role = Role.Tab; selected = activeType == "show" }) {
                    Text("Shows", color = CinemaColors.Foreground, fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(accountName, color = CinemaColors.Muted, fontSize = if (compact) 13.sp else 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 140.dp else 240.dp))
        }
    }
}

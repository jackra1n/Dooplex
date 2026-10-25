package com.jackra1n.dooplex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jackra1n.dooplex.ui.theme.DooplexTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var playerError by mutableStateOf<String?>(null)
    private val nativePlayer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val position = try {
            ZidooPlayer.readResult(result.data)
        } catch (exception: IllegalStateException) {
            playerError = exception.message ?: "The native player returned invalid result data."
            null
        }
        PlaybackService.finish(this, position)
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("dooplex", MODE_PRIVATE)
        val clientId = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("clientId", it).apply()
        }
        setContent {
            var server by rememberSaveable {
                mutableStateOf(preferences.getString("server", "") ?: "")
            }
            var ratingKey by rememberSaveable {
                mutableStateOf(preferences.getString("ratingKey", "") ?: "")
            }
            var token by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val status by PlaybackMonitor.state.collectAsState()

            DooplexTheme(isInDarkTheme = true) {
                ProofScreen(
                    server = server,
                    token = token,
                    ratingKey = ratingKey,
                    onServerChange = { server = it },
                    onTokenChange = { token = it },
                    onRatingKeyChange = { ratingKey = it },
                    busy = loading || status.running,
                    loading = loading,
                    error = error ?: playerError,
                    status = status,
                    onPlay = {
                        error = null
                        playerError = null
                        if (server.isBlank() || token.isBlank() || ratingKey.isBlank()) {
                            error = "Enter a Plex server, user token, and movie or episode ID."
                        } else {
                            loading = true
                            val connection = PlexConnection(server.trim(), token.trim(), clientId)
                            val key = ratingKey.trim()
                            lifecycleScope.launch {
                                try {
                                    val media = PlexClient(connection).loadMedia(key)
                                    val intent = ZidooPlayer(this@MainActivity).createIntent(media)
                                    preferences.edit()
                                        .putString("server", connection.serverUrl)
                                        .putString("ratingKey", key)
                                        .apply()
                                    PlaybackService.begin(this@MainActivity, connection, media)
                                    try {
                                        nativePlayer.launch(intent)
                                    } catch (exception: Exception) {
                                        PlaybackService.finish(this@MainActivity, null)
                                        throw IllegalStateException("Android could not launch Zidoo's native player.")
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (exception: Exception) {
                                    error = exception.message ?: "Playback could not be started."
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProofScreen(
    server: String,
    token: String,
    ratingKey: String,
    onServerChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRatingKeyChange: (String) -> Unit,
    busy: Boolean,
    loading: Boolean,
    error: String?,
    status: PlaybackProofStatus,
    onPlay: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Dooplex", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Native playback check",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Plex serves the original file. Zidoo handles playback. No SMB or NFS.")
            if (wide) {
                Row {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ConnectionFields(server, token, ratingKey, onServerChange, onTokenChange,
                            onRatingKeyChange, busy, loading, error, onPlay)
                    }
                    Spacer(Modifier.width(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusPanel(status)
                    }
                }
            } else {
                ConnectionFields(server, token, ratingKey, onServerChange, onTokenChange,
                    onRatingKeyChange, busy, loading, error, onPlay)
                StatusPanel(status)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionFields(
    server: String,
    token: String,
    ratingKey: String,
    onServerChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRatingKeyChange: (String) -> Unit,
    busy: Boolean,
    loading: Boolean,
    error: String?,
    onPlay: () -> Unit,
) {
    ProofField("Plex server URL", server, onServerChange, "http://your-server:32400", enabled = !busy)
    ProofField("Plex user token", token, onTokenChange, "Token for the user who is watching",
        secret = true, enabled = !busy)
    ProofField("Movie or episode ID", ratingKey, onRatingKeyChange, "Plex ratingKey, for example 12345",
        enabled = !busy)
    Text(
        "Use an individual movie or episode, not a show or season. The ID is the number " +
            "in /library/metadata/… in Plex Web's item URL. The token is kept in memory only.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onPlay, enabled = !busy) {
        Text(if (loading) "Contacting Plex…" else if (busy) "Native player session active" else "Play original with Zidoo")
    }
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProofField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            ),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false
                    else when (it.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .semantics { contentDescription = label }
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(16.dp),
            decorationBox = { field ->
                if (value.isEmpty()) {
                    Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusPanel(status: PlaybackProofStatus) {
    Text("Playback evidence", style = MaterialTheme.typography.titleMedium)
    Text(status.phase, color = MaterialTheme.colorScheme.primary)
    if (status.title.isNotBlank()) {
        Text(status.title, style = MaterialTheme.typography.titleMedium)
        Text("${formatTime(status.positionMs)} / ${formatTime(status.durationMs)}")
    }
    Text(status.detail)
    Spacer(Modifier.height(8.dp))
    Text(
        "Check the Plex dashboard for the correct user and a client named Dooplex. " +
            "Verify HDR / Dolby Vision and audio passthrough on the TV and AVR. " +
            "Pause, seek, resume, and stop to check synchronization.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
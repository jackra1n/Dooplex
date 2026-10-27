package com.jackra1n.dooplex.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.jackra1n.dooplex.MainActivity
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexConnection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackProofStatus(
    val phase: String = "Ready",
    val title: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val detail: String = "No playback has been started. Live state must come from the native player, not a timer.",
    val running: Boolean = false,
)

object PlaybackMonitor {
    val state = MutableStateFlow(PlaybackProofStatus())
}

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var session: Session? = null

    private class Session(val client: PlexClient, val media: PlaybackMedia, val live: Boolean) {
        val id: String = UUID.randomUUID().toString()
        var observedPositionMs: Long? = null
        var liveReported = false
        var liveFailure: String? = null
        var telemetryUnavailable = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Plex playback reporting", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> startSession(intent)
            FINISH -> finishSession(
                if (intent.hasExtra("resultPosition")) intent.getLongExtra("resultPosition", 0) else null,
            )
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (session != null) return
        startForeground(1, notification())
        val media = PlaybackMedia(
            ratingKey = intent.getStringExtra("ratingKey").orEmpty(),
            title = intent.getStringExtra("title").orEmpty(),
            durationMs = intent.getLongExtra("duration", 0),
            resumePositionMs = intent.getLongExtra("resume", 0),
            streamUrl = intent.getStringExtra("streamUrl").orEmpty(),
            partKey = intent.getStringExtra("partKey").orEmpty(),
            mediaIndex = intent.getIntExtra("mediaIndex", 0),
            partIndex = intent.getIntExtra("partIndex", 0),
        )
        val connection = PlexConnection(
            intent.getStringExtra("server").orEmpty(),
            intent.getStringExtra("token").orEmpty(),
            intent.getStringExtra("clientId").orEmpty(),
        )
        val current = Session(PlexClient(connection), media, live = intent.getBooleanExtra("liveStatus", false))
        session = current
        // Only Zidoo's player reports live progress; other players report where they stopped.
        if (!current.live) {
            PlaybackMonitor.state.value = PlaybackProofStatus(
                phase = "Playing in another app",
                title = media.title,
                durationMs = media.durationMs,
                detail = "Plex gets the position when the player closes.",
                running = true,
            )
            return
        }
        PlaybackMonitor.state.value = PlaybackProofStatus(
            phase = "Waiting for native player",
            title = media.title,
            durationMs = media.durationMs,
            detail = "No playing state is sent to Plex until matching native-player telemetry is received.",
            running = true,
        )
        pollJob = scope.launch {
            val player = ExternalPlayer(this@PlaybackService)
            try {
                while (isActive) {
                    delay(3_000)
                    val status = player.readStatus(media)
                    if (status == null) {
                        current.telemetryUnavailable = true
                        PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                            phase = "Waiting for matching native telemetry",
                            detail = "Native media identity or state is unavailable. Still sampling until the player returns; " +
                                "no unverified progress or playing state is sent to Plex.",
                        )
                        continue
                    }
                    current.telemetryUnavailable = false
                    current.observedPositionMs = status.positionMs
                    current.client.timeline(media, status.state, status.positionMs, current.id)
                    current.liveReported = true
                    PlaybackMonitor.state.value = PlaybackProofStatus(
                        phase = "Reported ${status.state} to Plex",
                        title = media.title,
                        positionMs = status.positionMs,
                        durationMs = status.durationMs.takeIf { it > 0 } ?: media.durationMs,
                        detail = "Plex accepted the timeline update. Check its dashboard to verify the user and session details.",
                        running = true,
                    )
                    if (status.state == "stopped") {
                        releaseSession()
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                current.liveFailure = exception.message ?: "Could not read native-player state or report it to Plex."
                PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                    phase = "Live reporting failed",
                    detail = current.liveFailure.orEmpty(),
                )
            }
        }
    }

    private fun finishSession(resultPositionMs: Long?) {
        val current = session
        if (current == null) {
            stopSelf()
            return
        }
        val polling = pollJob
        pollJob = null
        polling?.cancel()
        scope.launch {
            polling?.join()
            val position = resultPositionMs ?: current.observedPositionMs
            try {
                if (position == null) {
                    PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                        phase = "Player returned without a position",
                        detail = current.liveFailure
                            ?: "No verified playback position was available. No fabricated stop/progress update was sent to Plex.",
                    )
                } else {
                    current.client.timeline(current.media, "stopped", position, current.id)
                    PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                        phase = when {
                            !current.live -> "Reported stopped to Plex"
                            current.liveFailure != null -> "Stopped; live reporting failed"
                            !current.liveReported -> "Stopped; live telemetry unavailable"
                            current.telemetryUnavailable -> "Stopped; last live telemetry unavailable"
                            else -> "Reported stopped to Plex"
                        },
                        positionMs = position,
                        detail = current.liveFailure ?: when {
                            !current.live -> "Final position sent from the video player."
                            !current.liveReported ->
                                "Final position sent, but matching live telemetry was unavailable before the player returned."
                            current.telemetryUnavailable ->
                                "Final position sent from the player result or last verified position; " +
                                    "live telemetry was unavailable when the player returned."
                            else -> "Final position sent. Check Plex resume position and the end of the active session."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                    phase = "Final Plex update failed",
                    detail = listOfNotNull(
                        current.liveFailure,
                        exception.message ?: "Could not report the final position to Plex.",
                    ).joinToString(" "),
                )
            } finally {
                releaseSession()
            }
        }
    }

    private fun releaseSession() {
        session = null
        PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dooplex playback check")
            .setContentText("Reading native-player state and reporting it to Plex")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session = null
        PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(running = false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "plex-playback"
        private const val START = "com.jackra1n.dooplex.START_PLAYBACK"
        private const val FINISH = "com.jackra1n.dooplex.FINISH_PLAYBACK"

        fun begin(context: Context, connection: PlexConnection, media: PlaybackMedia, liveStatus: Boolean) {
            val intent = Intent(context, PlaybackService::class.java).setAction(START)
                .putExtra("liveStatus", liveStatus)
                .putExtra("server", connection.serverUrl)
                .putExtra("token", connection.token)
                .putExtra("clientId", connection.clientId)
                .putExtra("ratingKey", media.ratingKey)
                .putExtra("title", media.title)
                .putExtra("duration", media.durationMs)
                .putExtra("resume", media.resumePositionMs)
                .putExtra("streamUrl", media.streamUrl)
                .putExtra("partKey", media.partKey)
                .putExtra("mediaIndex", media.mediaIndex)
                .putExtra("partIndex", media.partIndex)
            ContextCompat.startForegroundService(context, intent)
        }

        fun finish(context: Context, positionMs: Long?) {
            val intent = Intent(context, PlaybackService::class.java).setAction(FINISH)
            if (positionMs != null) intent.putExtra("resultPosition", positionMs)
            context.startService(intent)
        }
    }
}

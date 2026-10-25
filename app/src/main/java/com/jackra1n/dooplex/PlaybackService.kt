package com.jackra1n.dooplex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
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
import java.util.UUID

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

    private class Session(val client: PlexClient, val media: PlaybackMedia) {
        val id: String = UUID.randomUUID().toString()
        var observedPositionMs: Long? = null
        var liveReported = false
        var liveFailure: String? = null
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
        val current = Session(PlexClient(connection), media)
        session = current
        PlaybackMonitor.state.value = PlaybackProofStatus(
            phase = "Waiting for native player",
            title = media.title,
            durationMs = media.durationMs,
            detail = "No playing state is sent to Plex until matching native-player telemetry is received.",
            running = true,
        )
        pollJob = scope.launch {
            val player = ZidooPlayer(this@PlaybackService)
            var missingStatusCount = 0
            try {
                while (isActive) {
                    delay(3_000)
                    val status = player.readStatus(media)
                    if (status == null) {
                        missingStatusCount++
                        PlaybackMonitor.state.value = PlaybackMonitor.state.value.copy(
                            phase = "Native player state unavailable",
                            detail = "No matching playback telemetry. No progress or playing state is being fabricated.",
                        )
                        if (missingStatusCount >= 10) {
                            throw IllegalStateException(
                                "The native player did not expose matching playback telemetry. " +
                                    "Live Plex reporting could not be established. Return from the player to inspect its result.",
                            )
                        }
                        continue
                    }
                    missingStatusCount = 0
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
                        phase = if (current.liveFailure != null) "Stopped; live reporting failed" else "Reported stopped to Plex",
                        positionMs = position,
                        detail = current.liveFailure ?: if (!current.liveReported) {
                            "The player returned a position, but no live telemetry was received before it returned."
                        } else {
                            "Final position sent. Check Plex resume position and the end of the active session."
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

        fun begin(context: Context, connection: PlexConnection, media: PlaybackMedia) {
            val intent = Intent(context, PlaybackService::class.java).setAction(START)
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

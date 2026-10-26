package com.jackra1n.dooplex

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import java.util.Locale

/**
 * Development builds send the breakdown of every slow frame to `mise run crashes`, since this box
 * offers no profiler. Composition shows up as "anim", Compose layout as "layout", display-list
 * recording as "draw", texture uploads as "sync" and GPU back-pressure as "cmd"/"swap".
 */
internal object FrameReporter {
    private const val FLUSH_MS = 4_000L
    private const val MAX_LINES = 40

    fun install(activity: Activity) {
        val url = BuildConfig.CRASH_REPORT_URL
        if (url.isEmpty()) return
        val handler = Handler(HandlerThread("frame-reporter").apply { start() }.looper)
        val budget = 1_000_000_000L / (activity.display?.refreshRate ?: 60f).toLong()
        val slow = ArrayList<String>()
        var frames = 0
        var janky = 0
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            frames++
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total > budget * 3 / 2) {
                janky++
                if (slow.size < MAX_LINES) slow += String.format(
                    Locale.ROOT, "%5.1f ms | delay %4.1f input %4.1f anim %4.1f layout %4.1f draw %4.1f sync %4.1f cmd %4.1f swap %4.1f",
                    total / 1e6, metrics.ms(FrameMetrics.UNKNOWN_DELAY_DURATION), metrics.ms(FrameMetrics.INPUT_HANDLING_DURATION),
                    metrics.ms(FrameMetrics.ANIMATION_DURATION), metrics.ms(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    metrics.ms(FrameMetrics.DRAW_DURATION), metrics.ms(FrameMetrics.SYNC_DURATION),
                    metrics.ms(FrameMetrics.COMMAND_ISSUE_DURATION), metrics.ms(FrameMetrics.SWAP_BUFFERS_DURATION),
                )
            }
        }, handler)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (janky > 0) {
                    CrashReporter.send(url, "Slow frames: $janky of $frames over ${FLUSH_MS / 1000} s\n" + slow.joinToString("\n"))
                    slow.clear()
                }
                frames = 0
                janky = 0
                handler.postDelayed(this, FLUSH_MS)
            }
        }, FLUSH_MS)
    }

    private fun FrameMetrics.ms(metric: Int) = getMetric(metric) / 1e6
}

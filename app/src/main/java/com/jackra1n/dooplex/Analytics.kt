package com.jackra1n.dooplex

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject

/**
 * Development builds stream what the app does, and how smoothly, to `mise run receiver`; off unless
 * the build sets DOOPLEX_ANALYTICS_URL. Each slow frame carries the last cause before it (a key, a
 * hero swap) and the screen, since this box offers no profiler. In frame timings, composition shows
 * as "anim", late Compose layout and display-list recording as "draw", texture uploads as "sync" and
 * GPU back-pressure as "cmd"/"swap".
 */
internal object Analytics {
    private const val FLUSH_MS = 4_000L
    private const val CAUSE_MS = 600L
    private const val MEMORY_EVERY = 8
    private val url = BuildConfig.ANALYTICS_URL
    val enabled = url.isNotEmpty()
    private val queue = ConcurrentLinkedQueue<String>()
    private val samples = ConcurrentHashMap<String, Sample>()
    @Volatile private var cause = ""
    @Volatile private var causeAt = 0L
    @Volatile var screen = ""

    private class Sample {
        var count = 0
        var total = 0.0
        var max = 0.0
    }

    fun start(activity: Activity) {
        if (!enabled) return
        val handler = Handler(HandlerThread("analytics").apply { start() }.looper)
        val render = activity.resources.displayMetrics
        val mode = activity.display?.mode
        val refresh = mode?.refreshRate ?: 60f
        event(
            "session",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.SDK_INT,
            "build" to BuildConfig.BUILD_TYPE,
            "render" to "${render.widthPixels}x${render.heightPixels}@${render.density}",
            "display" to "${mode?.physicalWidth}x${mode?.physicalHeight}@$refresh",
            "memoryClassMb" to activity.getSystemService(ActivityManager::class.java).memoryClass,
        )
        val budget = (1_000_000_000L / refresh).toLong()
        var frames = 0
        var slow = 0
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            frames++
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                event("first-frame", "msSinceStart" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            }
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total > budget * 3 / 2) {
                slow++
                event(
                    "slow-frame",
                    "ms" to total.ms(),
                    "cause" to recentCause(),
                    "delay" to metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION).ms(),
                    "anim" to metrics.getMetric(FrameMetrics.ANIMATION_DURATION).ms(),
                    "layout" to metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).ms(),
                    "draw" to metrics.getMetric(FrameMetrics.DRAW_DURATION).ms(),
                    "sync" to metrics.getMetric(FrameMetrics.SYNC_DURATION).ms(),
                    "cmd" to metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).ms(),
                    "swap" to metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).ms(),
                )
            }
        }, handler)
        var flushes = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (frames > 0) event("frames", "count" to frames, "slow" to slow)
                frames = 0
                slow = 0
                if (++flushes % MEMORY_EVERY == 0) memory()
                summarizeSamples()
                flush()
                handler.postDelayed(this, FLUSH_MS)
            }
        }, FLUSH_MS)
    }

    /** The cause the next slow frames are blamed on, such as "key:DPAD_DOWN" or "hero:swap". */
    fun mark(name: String) {
        if (!enabled) return
        cause = name
        causeAt = SystemClock.uptimeMillis()
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        val json = JSONObject().put("t", System.currentTimeMillis()).put("event", name).put("screen", screen)
        fields.forEach { (key, value) -> json.put(key, value) }
        queue += json.toString()
    }

    /** Aggregated per flush into one "stats" event: count, mean and max. */
    fun sample(name: String, value: Double) {
        if (!enabled) return
        val sample = samples.getOrPut(name) { Sample() }
        synchronized(sample) {
            sample.count++
            sample.total += value
            if (value > sample.max) sample.max = value
        }
    }

    inline fun <T> timed(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            sample(name, (System.nanoTime() - start) / 1e6)
        }
    }

    private fun recentCause(): String = if (SystemClock.uptimeMillis() - causeAt < CAUSE_MS) cause else ""

    private fun summarizeSamples() {
        if (samples.isEmpty()) return
        val stats = JSONObject()
        for ((name, sample) in samples) {
            synchronized(sample) {
                if (sample.count == 0) return@synchronized
                stats.put(name, JSONObject().put("n", sample.count).put("mean", (sample.total / sample.count).round())
                    .put("max", sample.max.round()))
                sample.count = 0
                sample.total = 0.0
                sample.max = 0.0
            }
        }
        if (stats.length() > 0) event("stats", "values" to stats)
    }

    private fun memory() {
        val runtime = Runtime.getRuntime()
        event(
            "memory",
            "javaMb" to (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
            "nativeMb" to Debug.getNativeHeapAllocatedSize() / 1_048_576,
            "pssMb" to Debug.getPss() / 1024,
        )
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val lines = ArrayList<String>()
        while (true) lines += queue.poll() ?: break
        if (!CrashReporter.send(url, lines.joinToString("\n"))) lines.takeLast(500).forEach { queue += it }
    }

    private fun Long.ms() = (this / 1e5).toLong() / 10.0

    private fun Double.round() = (this * 10).toLong() / 10.0
}

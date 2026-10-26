package com.jackra1n.dooplex

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
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
    private const val TRACE_BUFFER = 96 * 1024 * 1024
    private const val TRACE_INTERVAL_US = 1_000
    private val url = BuildConfig.ANALYTICS_URL
    private val traceUrl = url.removeSuffix("/events") + "/trace"
    val enabled = url.isNotEmpty()
    private lateinit var handler: Handler
    private lateinit var traceFile: File
    private var tracing = false
    private val main = Handler(Looper.getMainLooper())
    private val flags = mutableStateMapOf<String, String>()
    private var flagText = ""
    var onFlagsChanged: (() -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null

    /** A development switch set from the PC (analytics/flags), so experiments need no reinstall. */
    fun flag(name: String, default: Boolean = true): Boolean =
        flags[name]?.let { it == "1" || it == "true" } ?: default
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
        handler = Handler(HandlerThread("analytics").apply { start() }.looper)
        traceFile = File(activity.cacheDir, "dev.trace")
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
                    "vsyncUs" to metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) / 1000,
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
                // Sent even when idle, so the receiver's flags and commands always reach the app.
                event("frames", "count" to frames, "slow" to slow, "flags" to flagText)
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
        val body = lines.joinToString("\n").toByteArray()
        val reply = post(url, body.size.toLong()) { it.write(body) }
        if (reply == null) lines.takeLast(500).forEach { queue += it }
        // The receiver answers "trace <seconds>" after `mise run trace`, "do <command>" for tools/bench.py,
        // and always "flags ..." for experiments.
        reply?.lineSequence()?.forEach { line ->
            when {
                line.startsWith("trace ") -> line.removePrefix("trace ").trim().toIntOrNull()?.let { trace(it.coerceIn(1, 60)) }
                line.startsWith("flags") -> applyFlags(line.removePrefix("flags").trim())
                line.startsWith("do ") -> command(line.removePrefix("do ").trim())
            }
        }
    }

    private fun command(name: String) {
        event("command", "name" to name)
        main.post { onCommand?.invoke(name) }
    }

    private fun applyFlags(text: String) {
        if (text == flagText) return
        flagText = text
        val parsed = text.split(',').mapNotNull { it.split('=').takeIf { pair -> pair.size == 2 } }
            .associate { (key, value) -> key.trim() to value.trim() }
        event("flags", "values" to text)
        main.post {
            flags.clear()
            flags.putAll(parsed)
            onFlagsChanged?.invoke()
        }
    }

    /** ART's sampling profiler: every thread's stack each millisecond, uploaded for tools/trace-report.py. */
    private fun trace(seconds: Int) {
        if (tracing) return
        traceFile.delete()
        try {
            Debug.startMethodTracingSampling(traceFile.path, TRACE_BUFFER, TRACE_INTERVAL_US)
        } catch (failure: RuntimeException) {
            event("trace-failed", "error" to failure.toString())
            return
        }
        tracing = true
        event("trace-start", "seconds" to seconds, "monoUs" to System.nanoTime() / 1000)
        handler.postDelayed({
            Debug.stopMethodTracing()
            tracing = false
            val bytes = traceFile.length()
            val uploaded = post(traceUrl, bytes, timeoutMs = 120_000) { out -> traceFile.inputStream().use { it.copyTo(out) } } != null
            event("trace-done", "bytes" to bytes, "uploaded" to uploaded)
            traceFile.delete()
        }, seconds * 1000L)
    }

    private fun post(target: String, length: Long, timeoutMs: Int = 5_000, write: (OutputStream) -> Unit): String? = try {
        val http = URI(target).toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = 3_000
            http.readTimeout = timeoutMs
            http.doOutput = true
            http.setFixedLengthStreamingMode(length)
            http.outputStream.use(write)
            if (http.responseCode in 200..299) http.inputStream.use { String(it.readBytes()) } else null
        } finally {
            http.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun Long.ms() = (this / 1e5).toLong() / 10.0

    private fun Double.round() = (this * 10).toLong() / 10.0
}

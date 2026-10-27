package com.jackra1n.dooplex.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.jackra1n.dooplex.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Off unless the build sets DOOPLEX_CRASH_URL; `mise run upload` points it at `mise run receiver` on the dev PC. */
internal object CrashReporter {
    private const val TIMEOUT_MS = 3_000
    private const val MAX_TRACE_BYTES = 256 * 1024
    private const val STARTUP_WAIT_MS = 2_500L
    private const val CRASH_WAIT_MS = 5_000L
    private val normalExits = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
    )

    fun install(context: Context) {
        val url = BuildConfig.CRASH_REPORT_URL
        if (url.isEmpty()) return
        val pending = File(context.filesDir, "pending-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashed, error ->
            try {
                val trace = "Uncaught exception on thread ${crashed.name}\n\n${error.stackTraceToString()}"
                pending.writeText(trace)
                // The main thread may not use the network, so a helper thread collects and sends while this one waits.
                thread {
                    val report = report(context, trace)
                    pending.writeText(report)
                    if (send(url, report)) pending.delete()
                }.join(CRASH_WAIT_MS)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(crashed, error)
        }
        // Waiting for a pending crash lets an app that crashes right after launch still deliver it.
        val crashed = pending.exists()
        val sender = thread(name = "crash-reporter") {
            if (crashed && send(url, pending.readText())) pending.delete()
            reportPreviousExits(context, url)
        }
        if (crashed) sender.join(STARTUP_WAIT_MS)
    }

    private fun reportPreviousExits(context: Context, url: String) {
        val preferences = context.getSharedPreferences("crash-reporter", Context.MODE_PRIVATE)
        val reported = preferences.getLong("last-exit", 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 5)
            .filter { it.timestamp > reported }
        for (exit in exits.sortedBy { it.timestamp }) {
            if (exit.reason in normalExits) continue
            val details = buildString {
                appendLine("Previous run ended abnormally")
                appendLine("Reason: ${exit.reason}, status ${exit.status}, importance ${exit.importance}")
                appendLine("Description: ${exit.description}")
                appendLine("Memory: pss ${exit.pss} KiB, rss ${exit.rss} KiB")
                appendLine("Exited: ${Instant.ofEpochMilli(exit.timestamp)}")
                exit.traceInputStream?.use { appendLine().appendLine(readLimited(it)) }
            }
            if (!send(url, report(context, details))) return
        }
        exits.maxOfOrNull { it.timestamp }?.let { preferences.edit().putLong("last-exit", it).apply() }
    }

    private fun report(context: Context, body: String): String = buildString {
        appendLine(body.lineSequence().first())
        appendLine("App: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Time: ${Instant.now()}")
        appendLine()
        appendLine(body.trimEnd())
        appendLine()
        appendLine("Recent log:")
        append(recentLog(context))
    }

    // Apps may read their own log lines, including the ones left by the previous process.
    // Writing to a file with a deadline keeps a stuck logcat from blocking the report.
    private fun recentLog(context: Context): String = try {
        val file = File(context.cacheDir, "recent.log")
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "threadtime")
            .redirectErrorStream(true).redirectOutput(file).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        file.inputStream().use { readLimited(it) }
    } catch (_: Exception) {
        "unavailable"
    }

    private fun readLimited(input: InputStream): String {
        val bytes = ByteArray(MAX_TRACE_BYTES)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        return String(bytes, 0, size)
    }

    fun send(url: String, report: String): Boolean = try {
        val http = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.connectTimeout = TIMEOUT_MS
            http.readTimeout = TIMEOUT_MS
            http.doOutput = true
            http.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            http.outputStream.use { it.write(report.toByteArray()) }
            http.responseCode in 200..299
        } finally {
            http.disconnect()
        }
    } catch (_: Exception) {
        false
    }
}

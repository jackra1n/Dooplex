package com.jackra1n.dooplex

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlin.concurrent.thread

class DooplexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/** Off unless the build sets DOOPLEX_CRASH_URL; `mise run upload` points it at `mise run crashes` on the dev PC. */
internal object CrashReporter {
    private const val TIMEOUT_MS = 3_000
    private const val MAX_TRACE_BYTES = 256 * 1024
    private val abnormalExits = setOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    fun install(context: Context) {
        val url = BuildConfig.CRASH_REPORT_URL
        if (url.isEmpty()) return
        val pending = File(context.filesDir, "pending-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashed, error ->
            try {
                val report = report(context, "Uncaught exception on thread ${crashed.name}", error.stackTraceToString())
                pending.writeText(report)
                // The main thread may not use the network, so a helper thread sends while this one waits.
                thread { if (send(url, report)) pending.delete() }.join(TIMEOUT_MS.toLong())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(crashed, error)
        }
        thread(name = "crash-reporter") {
            if (pending.exists() && send(url, pending.readText())) pending.delete()
            reportPreviousExits(context, url)
        }
    }

    private fun reportPreviousExits(context: Context, url: String) {
        val preferences = context.getSharedPreferences("crash-reporter", Context.MODE_PRIVATE)
        val reported = preferences.getLong("last-exit", 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 5)
            .filter { it.timestamp > reported }
        for (exit in exits.sortedBy { it.timestamp }) {
            if (exit.reason !in abnormalExits) continue
            val details = buildString {
                appendLine("Reason: ${exit.reason}, status ${exit.status}, importance ${exit.importance}")
                appendLine("Description: ${exit.description}")
                appendLine("Memory: pss ${exit.pss} KiB, rss ${exit.rss} KiB")
                appendLine("Exited: ${Instant.ofEpochMilli(exit.timestamp)}")
                exit.traceInputStream?.use { appendLine().appendLine(readLimited(it)) }
            }
            if (!send(url, report(context, "Previous run ended abnormally", details))) return
        }
        exits.maxOfOrNull { it.timestamp }?.let { preferences.edit().putLong("last-exit", it).apply() }
    }

    private fun report(context: Context, title: String, body: String): String = buildString {
        appendLine(title)
        appendLine("App: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Time: ${Instant.now()}")
        appendLine()
        appendLine(body.trimEnd())
        appendLine()
        appendLine("Recent log:")
        append(recentLog())
    }

    // Apps may read their own log lines, including the ones left by the previous process.
    private fun recentLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "threadtime").redirectErrorStream(true).start()
        process.inputStream.use { readLimited(it) }.also { process.destroy() }
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

    private fun send(url: String, report: String): Boolean = try {
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

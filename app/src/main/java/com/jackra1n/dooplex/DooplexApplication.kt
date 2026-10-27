package com.jackra1n.dooplex

import android.app.Application
import android.content.Context
import com.jackra1n.dooplex.diagnostics.CrashReporter

class DooplexApplication : Application() {
    // Content providers start before onCreate, so the reporter goes in first to see their crashes too.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReporter.install(base)
    }
}

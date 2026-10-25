package dev.fengine.wallpaper

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.SettingsStore
import kotlin.concurrent.thread

class FengineApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        recordCrashes()
        thread(name = "exit-log", priority = Thread.MIN_PRIORITY) { importExits() }
    }

    /** An uncaught exception gets a ledger line (class and site, no message) before it ends us. */
    private fun recordCrashes() {
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val root = generateSequence(e) { it.cause }.last()
                val frames =
                    (root.stackTrace + e.stackTrace).map {
                        "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                LedgerStore(this)
                    .record(Ledger.Event.Crash(Ledger.crashSite(root.javaClass.name, frames)))
            }
            next?.uncaughtException(t, e)
        }
    }

    /**
     * Earlier process exits as Android recorded them (crash, ANR, low memory, force stop), each
     * once. Android 11+; nothing to read before that.
     */
    private fun importExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val exits =
                am.getHistoricalProcessExitReasons(packageName, 0, 16).map {
                    Triple(it.timestamp, it.reason, it.importance)
                }
            val ledger = LedgerStore(this)
            Ledger.newExits(exits, ledger.lastExitAt()).forEach { ledger.record(it.event, it.atMs) }
        }
    }
}

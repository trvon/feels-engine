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
                // A cause chain can loop (initCause allows A -> B -> A): bounded.
                val root = generateSequence(e) { it.cause }.take(16).last()
                val frames =
                    (root.stackTrace + e.stackTrace).map {
                        "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                LedgerStore(this)
                    .record(Ledger.Event.Crash(Ledger.crashSite(root.javaClass.name, frames)))
            }
            if (next != null) next.uncaughtException(t, e)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
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
            val fresh = Ledger.newExits(exits, ledger.lastExitAt(), Build.VERSION.SDK_INT)
            fresh.forEach { ledger.record(it.event, it.atMs) }
            exits.maxOfOrNull { it.first }?.let(ledger::markExitsUpTo)
        }
    }
}

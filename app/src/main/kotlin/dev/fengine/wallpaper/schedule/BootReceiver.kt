package dev.fengine.wallpaper.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fengine.wallpaper.FengineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Alarms do not survive reboot; WorkManager periodic work does. Re-sync both from settings after
 * boot and after clock or zone changes so TIME_OF_DAY slots land on the new local time.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        val app = context.applicationContext as FengineApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) { RotationScheduler(app).sync(app.settings.read()) }
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "schedule sync failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
    }
}

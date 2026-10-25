package dev.fengine.wallpaper.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fengine.wallpaper.FengineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The scheduled change, for both rhythms (the name predates interval alarms). Runs the rotation
 * inline within the broadcast's grace time: a while-idle alarm puts the app on a short allowlist,
 * and handing the work to WorkManager would put it back under the job quota that stalled it.
 */
class TimeOfDayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as FengineApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.settings.read().enabled)
                    Rotation.run(app, source = dev.fengine.wallpaper.data.Ledger.Source.ALARM)
            } catch (e: Exception) {
                // A crash here would also end the live engine (same process); log and carry on.
                android.util.Log.w("TimeOfDayAlarm", "rotation failed", e)
            } finally {
                // Always the next alarm, from the new last-change time: a failed run must not end
                // the chain.
                runCatching { RotationScheduler(app).armNextAlarm(app.settings.read()) }
                pending.finish()
            }
        }
    }
}

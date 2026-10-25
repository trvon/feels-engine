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
                val scheduler = RotationScheduler(app)
                if (app.settings.read().enabled) {
                    Rotation.run(app, source = dev.fengine.wallpaper.data.Ledger.Source.ALARM)
                    scheduler.armNextAlarm(app.settings.read()) // from the new last-change time
                }
            } finally {
                pending.finish()
            }
        }
    }
}

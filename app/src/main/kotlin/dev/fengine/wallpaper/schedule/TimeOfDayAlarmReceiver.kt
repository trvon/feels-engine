package dev.fengine.wallpaper.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fengine.wallpaper.FengineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires at a configured clock time: run one rotation, then arm the next slot. */
class TimeOfDayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as FengineApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.settings.read()
                val scheduler = RotationScheduler(app)
                if (settings.enabled && settings.mode == dev.fengine.wallpaper.data.Mode.TIME_OF_DAY) {
                    scheduler.rotateNow()
                    scheduler.armNextAlarm(settings)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

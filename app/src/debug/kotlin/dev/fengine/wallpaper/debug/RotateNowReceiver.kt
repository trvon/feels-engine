package dev.fengine.wallpaper.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fengine.wallpaper.schedule.RotationScheduler

/** `adb shell am broadcast -a dev.fengine.wallpaper.ROTATE_NOW -p dev.fengine.wallpaper` runs one rotation. */
class RotateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RotationScheduler(context).rotateNow()
    }
}

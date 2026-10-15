package dev.fengine.wallpaper.debug

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.fengine.wallpaper.schedule.RotationScheduler
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper

/**
 * `adb shell am broadcast -a dev.fengine.wallpaper.ROTATE_NOW -p dev.fengine.wallpaper` runs one rotation.
 * `... -a dev.fengine.wallpaper.PROBE` logs what WallpaperManager answers per `which`, including the
 * Samsung display bits, so a foldable's per-panel live state can be read without dumpsys.
 */
class RotateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.fengine.wallpaper.PROBE") return probe(context)
        RotationScheduler(context).rotateNow()
    }

    private fun probe(context: Context) {
        val wm = WallpaperManager.getInstance(context)
        val mine = LiveWallpaper.component(context)
        for (which in listOf(1, 2, 3, 5, 6, 9, 10, 17, 18)) {
            val info = runCatching { wm.getWallpaperInfo(which) }.map { it?.component?.let { c -> if (c == mine) "ours" else c.flattenToShortString() } ?: "static" }
                .getOrElse { "error ${it.javaClass.simpleName}" }
            val id = runCatching { wm.getWallpaperId(which) }.getOrElse { -99 }
            Log.i("Probe", "which=$which live=$info id=$id")
        }
        Log.i("Probe", "activeOnHome=${LiveWallpaper.activeOnHome(context)} activeOnLock=${LiveWallpaper.activeOnLock(context)}")
    }
}

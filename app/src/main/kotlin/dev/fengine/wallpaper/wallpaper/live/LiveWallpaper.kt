package dev.fengine.wallpaper.wallpaper.live

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.fengine.wallpaper.data.Platform
import dev.fengine.wallpaper.wallpaper.SamsungDisplays

/** Whether this app's live engine is the active wallpaper, per screen; and the intent that offers it. */
object LiveWallpaper {
    fun component(context: Context) = ComponentName(context, PhotoWallpaperService::class.java)

    /** Which screens the engine draws, per panel. On a phone the cover answers equal the inner ones. */
    data class State(val innerHome: Boolean, val innerLock: Boolean, val coverHome: Boolean, val coverLock: Boolean)

    /**
     * Reads the live state per panel. One UI answers `getWallpaperInfo(which)` for the panel named
     * by the display bits (4 inner, 16 cover) and, with no bits, for whichever panel the hinge
     * makes active: asked plainly on a closed Fold it reports the cover panel's static wallpaper
     * while the engine draws the inner screen (probe on the Galaxy Z Fold 8, One UI 9, issue #1).
     * So every question here carries the bits on devices that have them.
     */
    fun state(context: Context, samsung: Boolean = SamsungDisplays.hasDisplayBits()): State {
        val wm = WallpaperManager.getInstance(context)
        val mine = component(context)
        fun home(panel: Int): Boolean {
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) wm.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM or panel)?.component == mine
                else wm.wallpaperInfo?.component == mine   // one answer for every panel before Android 14
            }.getOrDefault(false)
            return Platform.homeIsLive(Build.VERSION.SDK_INT, info, LiveEngines.anyRunning)
        }
        fun lock(panel: Int): Boolean {
            val ours = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                runCatching { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK or panel) }.getOrNull()?.let { it.component == mine }
            else null
            val lockId = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK or panel) }.getOrDefault(0)
            return Platform.lockIsLive(Build.VERSION.SDK_INT, home(panel), ours, lockId)
        }
        if (!samsung) {
            val h = home(0); val l = lock(0)
            return State(h, l, h, l)
        }
        return State(
            home(SamsungDisplays.FLAG_DISPLAY_PHONE), lock(SamsungDisplays.FLAG_DISPLAY_PHONE),
            home(SamsungDisplays.FLAG_DISPLAY_SUB), lock(SamsungDisplays.FLAG_DISPLAY_SUB),
        )
    }

    /** True when the (inner) home screen is drawn by [PhotoWallpaperService]. */
    fun activeOnHome(context: Context): Boolean = state(context).innerHome

    /** True when the (inner) lock screen is drawn by the engine. */
    fun activeOnLock(context: Context): Boolean = state(context).innerLock

    /** Opens the system's live wallpaper preview with this engine selected; the user confirms there. */
    fun chooserIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
}

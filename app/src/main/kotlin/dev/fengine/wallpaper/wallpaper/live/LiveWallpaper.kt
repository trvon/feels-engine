package dev.fengine.wallpaper.wallpaper.live

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Whether this app's live engine is the active wallpaper, per screen; and the intent that offers it. */
object LiveWallpaper {
    fun component(context: Context) = ComponentName(context, PhotoWallpaperService::class.java)

    /** True when the home screen is drawn by [PhotoWallpaperService]. */
    fun activeOnHome(context: Context): Boolean =
        WallpaperManager.getInstance(context).wallpaperInfo?.component == component(context)

    /**
     * True when the lock screen is drawn by the engine. Android 13+ can answer per screen; before
     * that a live wallpaper always covers both, so the home answer stands for the lock screen too.
     */
    fun activeOnLock(context: Context): Boolean {
        val wm = WallpaperManager.getInstance(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The lock screen shows the home wallpaper when it has none of its own.
            val lock = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
            if (lock != null) lock.component == component(context)
            else wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0 && activeOnHome(context)
        } else activeOnHome(context)
    }

    /** Opens the system's live wallpaper preview with this engine selected; the user confirms there. */
    fun chooserIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
}

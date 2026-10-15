package dev.fengine.wallpaper.wallpaper.live

import java.util.concurrent.ConcurrentHashMap

/**
 * The engines this process is running as the actual wallpaper (previews excluded). Below
 * Android 14 the app cannot reliably ask WallpaperManager whether its own engine is active
 * (see `Platform.homeIsLive`), but while it is, the system keeps this process bound to it, so
 * the engines themselves are the answer.
 */
object LiveEngines {
    private val running = ConcurrentHashMap<Int, Boolean>()

    fun created(id: Int, preview: Boolean) { if (!preview) running[id] = true }
    fun destroyed(id: Int) { running.remove(id) }
    val anyRunning: Boolean get() = running.isNotEmpty()
}

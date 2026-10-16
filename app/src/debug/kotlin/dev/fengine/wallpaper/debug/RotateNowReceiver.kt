package dev.fengine.wallpaper.debug

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.schedule.RotationScheduler
import dev.fengine.wallpaper.smart.IndexLibraryWorker
import dev.fengine.wallpaper.smart.SmartGroupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper

/**
 * `adb shell am broadcast -a dev.fengine.wallpaper.ROTATE_NOW -p dev.fengine.wallpaper` runs one rotation.
 * `... -a dev.fengine.wallpaper.PROBE` logs what WallpaperManager answers per `which`, including the
 * Samsung display bits, so a foldable's per-panel live state can be read without dumpsys.
 * `... -a dev.fengine.wallpaper.SEED` replaces the settings with one group "Smoke" of eight photos
 * painted into private storage, on both screens, with the description "outdoor" looking through
 * them so a scan has work. Used by scripts/smoke-matrix.sh on every Android version.
 * `... -a dev.fengine.wallpaper.SCAN` labels the library and runs the description scan now.
 */
class RotateNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.fengine.wallpaper.PROBE") return probe(context)
        if (intent.action == "dev.fengine.wallpaper.SEED") return seed(context)
        if (intent.action == "dev.fengine.wallpaper.SCAN") {
            IndexLibraryWorker.enqueue(context); SmartGroupWorker.enqueue(context)
            Log.i("Smoke", "scan enqueued"); return
        }
        RotationScheduler(context).rotateNow()
    }

    private fun seed(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // No photo permission: the seed paints its own photos in private storage (file URIs),
                // the same bytes every run, and hands them to the group and to its description.
                val dir = java.io.File(context.filesDir, "smoke").apply { mkdirs() }
                val photos = (0 until 8).map { i ->
                    val f = java.io.File(dir, "smoke-$i.jpg")
                    if (!f.exists()) {
                        val bmp = android.graphics.Bitmap.createBitmap(1200, 1600, android.graphics.Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(bmp)
                        val rnd = java.util.Random(i.toLong())
                        c.drawColor(android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
                        val paint = android.graphics.Paint()
                        repeat(20) {
                            paint.color = android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                            c.drawCircle(rnd.nextFloat() * 1200, rnd.nextFloat() * 1600, 40f + rnd.nextFloat() * 200, paint)
                        }
                        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                    }
                    android.net.Uri.fromFile(f).toString()
                }
                val group = Group(name = "Smoke", photos = photos, pool = photos, rule = "outdoor")
                (context.applicationContext as FengineApp).settings.update {
                    Settings(groups = listOf(group), homeGroupId = group.id, lockGroupId = group.id, intervalMinutes = it.intervalMinutes)
                }
                Log.i("Smoke", "seeded ${photos.size} photos")
            } catch (e: Exception) {
                Log.w("Smoke", "seed failed", e)
            } finally { pending.finish() }
        }
    }

    private fun probe(context: Context) {
        val wm = WallpaperManager.getInstance(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.i("Probe", "sdk=${Build.VERSION.SDK_INT} info=${wm.wallpaperInfo?.component?.flattenToShortString()} " +
                "systemId=${wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)} lockId=${wm.getWallpaperId(WallpaperManager.FLAG_LOCK)}")
            Log.i("Probe", "activeOnHome=${LiveWallpaper.activeOnHome(context)} activeOnLock=${LiveWallpaper.activeOnLock(context)}")
            return
        }
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

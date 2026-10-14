package dev.fengine.wallpaper.schedule

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.wallpaper.WallpaperApplier
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import java.text.DateFormat
import java.util.Date

/**
 * One rotation step: for each screen with a group, pick that group's next photo, apply it, and
 * advance the group's cursor. Enqueued periodically (INTERVAL) or by the alarm receiver (TIME_OF_DAY).
 */
class RotateWallpaperWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = (applicationContext as FengineApp).settings
        val settings = store.read()
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        // Screens the live engine draws itself only need the cursor to move; it watches the settings.
        val plan = RotationPlan.advance(
            settings.homeGroupId, settings.lockGroupId,
            photos = { id -> settings.group(id)?.allPhotos.orEmpty() },
            cursor = { id -> settings.group(id)?.cursor ?: -1 },
            shuffle = settings.shuffle,
            liveHome = LiveWallpaper.activeOnHome(applicationContext),
            liveLock = LiveWallpaper.activeOnLock(applicationContext),
        )
        if (plan.cursors.isEmpty()) {
            store.update { it.copy(lastResult = "Nothing to show yet") }
            return Result.success()
        }

        val applier = WallpaperApplier(applicationContext)
        val failures = mutableListOf<String>()
        val advanced = plan.cursors
        for ((id, index) in plan.cursors) if (plan.writes.none { it.groupId == id }) {
            Log.i(TAG, "${settings.group(id)?.name}: photo ${index + 1} -> live engine")
        }
        for (write in plan.writes) {
            val group = settings.group(write.groupId) ?: continue
            when (val outcome = applier.apply(write.uri.toUri(), write.which)) {
                is WallpaperApplier.Result.Applied ->
                    Log.i(TAG, "${group.name}: photo ${write.index + 1} -> which=${write.which}, ids ${outcome.wallpaperIds}")
                is WallpaperApplier.Result.Failed -> {
                    Log.w(TAG, "${group.name}: ${outcome.reason}")
                    failures += "${group.name}: ${outcome.reason}"
                }
            }
        }

        store.update { s ->
            s.copy(
                groups = s.groups.map { g -> advanced[g.id]?.let { g.copy(cursor = it) } ?: g },
                lastResult = if (failures.isEmpty()) "Changed at $stamp"
                else "Problem at $stamp: ${failures.joinToString("; ")}",
            )
        }
        return if (failures.isEmpty()) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "RotateWallpaperWorker"
        const val PERIODIC_NAME = "rotate-wallpaper-periodic"
        const val ONE_SHOT_NAME = "rotate-wallpaper-once"
    }
}

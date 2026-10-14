package dev.fengine.wallpaper.schedule

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.wallpaper.WallpaperApplier
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
        val steps = RotationPlan.steps(settings.homeGroupId, settings.lockGroupId) { id ->
            settings.group(id)?.allPhotos.isNullOrEmpty()
        }
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        if (steps.isEmpty()) {
            store.update { it.copy(lastResult = "Nothing to show yet") }
            return Result.success()
        }

        val applier = WallpaperApplier(applicationContext)
        val failures = mutableListOf<String>()
        val advanced = mutableMapOf<String, Int>()
        for (step in steps) {
            val group = settings.group(step.groupId) ?: continue
            val pool = group.allPhotos
            val index = advanced[group.id]
                ?: RotationPlan.nextIndex(pool.size, group.cursor, settings.shuffle)
            advanced[group.id] = index
            when (val outcome = applier.apply(pool[index].toUri(), step.which)) {
                is WallpaperApplier.Result.Applied ->
                    Log.i(TAG, "${group.name}: photo ${index + 1} -> which=${step.which}, ids ${outcome.wallpaperIds}")
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

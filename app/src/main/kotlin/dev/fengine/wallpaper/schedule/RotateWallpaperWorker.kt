package dev.fengine.wallpaper.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.data.Ledger

/**
 * A rotation run through WorkManager: the in-app actions (one-shot) and the periodic backstop for
 * interval mode. The alarm is the main scheduled path (RotationScheduler); both call [Rotation].
 */
class RotateWallpaperWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // "Show this one now" pins an index per group; "Still" forces static writes over a live
        // engine
        // (setBitmap replaces it, which is how the look switches back without the system chooser).
        val pinned =
            inputData.keyValueMap
                .filterKeys { it.startsWith(KEY_PIN_PREFIX) }
                .map { (k, v) -> k.removePrefix(KEY_PIN_PREFIX) to (v as Int) }
                .toMap()
        val source = inputData.getString(KEY_SOURCE) ?: Ledger.Source.SCHEDULE
        val outcome =
            Rotation.run(
                applicationContext,
                source = source,
                pinned = pinned,
                forceStatic = inputData.getBoolean(KEY_FORCE_STATIC, false),
            )
        // Only scheduled runs retry: a retried Next, tile or widget would advance again.
        val failed =
            outcome is Rotation.Outcome.Done &&
                outcome.failures.isNotEmpty() &&
                Rotation.isScheduled(source)
        return if (!failed) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "RotateWallpaperWorker"
        const val PERIODIC_NAME = "rotate-wallpaper-periodic"
        const val ONE_SHOT_NAME = "rotate-wallpaper-once"
        /** Input key prefix: "$KEY_PIN_PREFIX<groupId>" -> index to show instead of advancing. */
        const val KEY_PIN_PREFIX = "pin:"
        /** Input key: write static photos even where the live engine is active. */
        const val KEY_FORCE_STATIC = "force_static"
        /** Input key: what asked for this rotation (`Ledger.Source`). */
        const val KEY_SOURCE = "source"
    }
}

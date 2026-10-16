package dev.fengine.wallpaper.schedule

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
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
        // Photos found through the library permission cannot be read any more: drop them first.
        if (store.read().groups.any { it.withoutLibraryFinds() != it }) store.update { s -> s.copy(groups = s.groups.map { it.withoutLibraryFinds() }) }
        val settings = store.read()
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        // "Show this one now" pins an index per group; "Still" forces static writes over a live engine
        // (setBitmap replaces it, which is how the look switches back without the system chooser).
        val pinned = inputData.keyValueMap
            .filterKeys { it.startsWith(KEY_PIN_PREFIX) }
            .map { (k, v) -> k.removePrefix(KEY_PIN_PREFIX) to (v as Int) }.toMap()
        val forceStatic = inputData.getBoolean(KEY_FORCE_STATIC, false)
        val source = inputData.getString(KEY_SOURCE) ?: Ledger.Source.SCHEDULE
        val ledger = LedgerStore(applicationContext)
        val live = if (forceStatic) LiveWallpaper.State(false, false, false, false) else LiveWallpaper.state(applicationContext)
        Log.i(TAG, "live: $live")
        // Screens the live engine draws itself only need the cursor to move; it watches the settings.
        val plan = RotationPlan.advance(
            settings.homeGroupId, settings.lockGroupId,
            photos = { id -> settings.group(id)?.rotation.orEmpty() },
            cursor = { id -> settings.group(id)?.cursor ?: -1 },
            shuffle = settings.shuffle,
            liveHome = live.innerHome, liveLock = live.innerLock,
            pinned = pinned,
            coverGroupId = settings.coverGroupId,
            coverLiveHome = live.coverHome, coverLiveLock = live.coverLock,
        )
        if (plan.cursors.isEmpty()) {
            store.update { it.copy(lastResult = "Nothing to show yet") }
            return Result.success()
        }

        val applier = WallpaperApplier(applicationContext)
        val failures = mutableListOf<String>()
        val unreadable = mutableListOf<Pair<String, String>>()
        val advanced = plan.cursors
        for ((id, index) in plan.cursors) if (plan.writes.none { it.groupId == id }) {
            Log.i(TAG, "${settings.group(id)?.name}: photo ${index + 1} -> live engine")
            ledger.record(Ledger.Event.Rotation(source, 0, 0, "live"))
        }
        for (write in plan.writes) {
            val group = settings.group(write.groupId) ?: continue
            when (val outcome = applier.apply(write.uri.toUri(), write.which, write.panels)) {
                is WallpaperApplier.Result.Applied -> {
                    Log.i(TAG, "${group.name}: photo ${write.index + 1} -> which=${write.which} panels=${write.panels}, ids ${outcome.wallpaperIds}")
                    ledger.record(Ledger.Event.Rotation(source, write.which, write.panels, "applied"))
                }
                is WallpaperApplier.Result.Unreadable -> {
                    Log.w(TAG, "${group.name}: photo ${write.index + 1} unreadable (${outcome.reason}); dropped from the group")
                    ledger.record(Ledger.Event.Rotation(source, write.which, write.panels, "unreadable"))
                    unreadable += write.groupId to write.uri
                }
                is WallpaperApplier.Result.Failed -> {
                    ledger.record(Ledger.Event.Rotation(source, write.which, write.panels, "failed"))
                    Log.w(TAG, "${group.name}: ${outcome.reason}")
                    failures += "${group.name}: ${outcome.reason}"
                }
            }
        }

        store.update { s ->
            s.copy(
                groups = s.groups.map { g -> advanced[g.id]?.let { g.copy(cursor = it) } ?: g }
                    .map { g -> unreadable.filter { it.first == g.id }.fold(g) { acc, (_, uri) -> acc.withoutPhoto(uri) } },
                lastResult = if (failures.isEmpty()) "Changed at $stamp"
                else "Problem at $stamp: ${failures.joinToString("; ")}",
            )
        }
        // A dropped photo leaves its screen on the old picture: try the next one, a few times at most.
        if (unreadable.isNotEmpty() && inputData.getInt(KEY_HOPS, 0) < 3) {
            RotationScheduler(applicationContext).rotateNow(source = source, hops = inputData.getInt(KEY_HOPS, 0) + 1)
        }
        return if (failures.isEmpty()) Result.success()
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
        /** Input key: how many unreadable photos this rotation already skipped past. */
        const val KEY_HOPS = "hops"
    }
}

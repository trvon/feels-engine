package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.GrantPlan
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.data.Platform
import dev.fengine.wallpaper.schedule.AppSleep

/**
 * Brings [PhotoCopies] in line with the groups: saves a copy of every photo that has none, deletes
 * copies no group uses, or deletes them all when keeping copies is off. Runs when the app opens and
 * after photos are added, in budgeted slices.
 */
class PhotoCopyWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = (context as FengineApp).settings.read()
        val photos = settings.groups.flatMap { it.allPhotos }
        val plan = CopyPlan.plan(photos, PhotoCopies.names(context), settings.keepCopies)
        PhotoCopies.delete(context, plan.delete)
        val pruned = plan.delete.size
        val started = SystemClock.elapsedRealtime()
        var saved = 0
        var missing = 0
        var outOfTime = false
        val copied = mutableListOf<String>()
        for (uri in plan.save) {
            if (isStopped) return Result.success()
            if (SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS) {
                outOfTime = true
                break
            }
            if (PhotoCopies.save(context, uri)) {
                saved++
                copied += uri
            } else missing++
        }
        // A photo set aside earlier that could be copied now is readable again: back into rotation
        // without waiting for the app to open.
        if (copied.isNotEmpty())
            context.settings.update { s ->
                s.copy(groups = s.groups.map { it.markReadable(copied) })
            }
        Log.i(
            TAG,
            "photos ${plan.save.size} to copy, saved $saved, unreadable $missing, pruned $pruned",
        )
        val ledger = LedgerStore(context)
        val used = PhotoCopies.takeServed()
        if (saved + missing + used > 0) ledger.record(Ledger.Event.Copy(saved, missing, used))
        upkeep(context, ledger)
        if (outOfTime) enqueue(context)
        return Result.success()
    }

    /**
     * Once a day: take the grants of every shown photo again (Android drops the oldest grant first,
     * by creation time, and taking one again resets it), release look-through grants above the
     * high-water mark, and log the count and Android's sleep status (GrantPlan, AppSleep).
     */
    private suspend fun upkeep(context: Context, ledger: LedgerStore) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!GrantPlan.retakeDue(prefs.getLong(KEY_UPKEEP, 0), now)) return
        val resolver = context.contentResolver
        val groups = (context as FengineApp).settings.read().groups
        for (uri in GrantPlan.retake(groups)) runCatching {
            resolver.takePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val held =
            resolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() to it.persistedTime }
        val release = GrantPlan.release(held, groups, Platform.grantCeiling(Build.VERSION.SDK_INT))
        for (uri in release) runCatching {
            resolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (release.isNotEmpty()) {
            // Without a grant they cannot be looked through again; their labels and verdicts stay.
            val gone = release.map(PhotoId::identity).toSet()
            context.settings.update { s ->
                s.copy(
                    groups =
                        s.groups.map { g ->
                            g.copy(pool = g.pool.filterNot { PhotoId.identity(it) in gone })
                        }
                )
            }
        }
        ledger.record(
            Ledger.Event.Grants(held.size - release.size, release.size, AppSleep.status(context))
        )
        prefs.edit { putLong(KEY_UPKEEP, now) }
    }

    companion object {
        const val TAG = "PhotoCopyWorker"
        private const val UNIQUE_NAME = "photo-copies"
        private const val TIME_BUDGET_MS = 8 * 60_000L
        private const val PREFS = "upkeep"
        private const val KEY_UPKEEP = "last_upkeep"

        /** Appends, so photos added while a run is going get their copies on the next one. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<PhotoCopyWorker>()
                        .setConstraints(
                            Constraints.Builder().setRequiresStorageNotLow(true).build()
                        )
                        .build(),
                )
        }
    }
}

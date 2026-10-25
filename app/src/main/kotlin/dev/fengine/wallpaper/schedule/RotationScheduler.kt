package dev.fengine.wallpaper.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.smart.IndexLibraryWorker
import dev.fengine.wallpaper.smart.SmartGroupWorker
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Arms or disarms the background trigger for the current settings.
 *
 * INTERVAL: WorkManager periodic work. The platform floor is 15 minutes and delivery is inexact
 * (flex window, Doze batching). No permission needed. TIME_OF_DAY:
 * AlarmManager.setAndAllowWhileIdle (inexact, fires during Doze within the system's batching
 * window). Chosen over exact alarms because SCHEDULE_EXACT_ALARM is user-granted and
 * USE_EXACT_ALARM is reserved for alarm/calendar apps by Play policy. A few minutes of drift is
 * acceptable for a wallpaper.
 */
class RotationScheduler(private val context: Context) {

    fun sync(settings: Settings) {
        syncSmartScan(settings)
        if (!settings.enabled || !settings.canRotate) {
            cancelAll()
            return
        }
        when (settings.mode) {
            Mode.INTERVAL -> {
                // Main path: a chained inexact alarm. A rarely opened app gets about one alarm an
                // hour
                // but only a few job sessions a day (RARE bucket), which stalled rotation for
                // hours.
                armNextAlarm(settings)
                // Backstop: periodic work, which skips when the alarm already changed the photo.
                val minutes =
                    settings.intervalMinutes.coerceAtLeast(Settings.MIN_INTERVAL_MINUTES).toLong()
                // A wallpaper change is not worth a wake-up on a low battery; the system batches
                // the
                // rest within the period's flex window and defers it in Doze.
                val request =
                    PeriodicWorkRequestBuilder<RotateWallpaperWorker>(minutes, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build()
                        )
                        .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        RotateWallpaperWorker.PERIODIC_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request,
                    )
            }
            Mode.TIME_OF_DAY -> {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(RotateWallpaperWorker.PERIODIC_NAME)
                armNextAlarm(settings)
            }
        }
    }

    /** Described groups keep looking for new photos a few times a day; nothing runs without one. */
    private fun syncSmartScan(settings: Settings) {
        val wm = WorkManager.getInstance(context)
        if (!settings.hasDescribedGroups) {
            wm.cancelUniqueWork(SmartGroupWorker.PERIODIC_NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(
            SmartGroupWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SmartGroupWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** One scan now, for a new or edited description. */
    fun scanNow() = SmartGroupWorker.enqueue(context)

    /** Label the library now (budgeted slices, resumes itself) and keep it fresh twice a day. */
    fun indexNow() {
        IndexLibraryWorker.enqueue(context)
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                IndexLibraryWorker.PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IndexLibraryWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build(),
            )
    }

    /**
     * Runs one rotation immediately through WorkManager so the worker path is exercised. [pinned]
     * shows those indexes instead of advancing; [forceStatic] writes still photos over a live
     * engine.
     */
    fun rotateNow(
        pinned: Map<String, Int> = emptyMap(),
        forceStatic: Boolean = false,
        source: String = Ledger.Source.NEXT,
    ) {
        val data =
            Data.Builder()
                .putBoolean(RotateWallpaperWorker.KEY_FORCE_STATIC, forceStatic)
                .putString(RotateWallpaperWorker.KEY_SOURCE, source)
        for ((id, index) in pinned) data.putInt(RotateWallpaperWorker.KEY_PIN_PREFIX + id, index)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                RotateWallpaperWorker.ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RotateWallpaperWorker>()
                    .setInputData(data.build())
                    .build(),
            )
    }

    fun armNextAlarm(settings: Settings) {
        if (!settings.enabled || !settings.canRotate) return cancelAlarm()
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now).toLong()
        val at =
            when (settings.mode) {
                Mode.INTERVAL ->
                    RotationPlan.nextIntervalAt(
                        now,
                        settings.lastRotationAt,
                        settings.intervalMinutes.coerceAtLeast(Settings.MIN_INTERVAL_MINUTES),
                    )
                Mode.TIME_OF_DAY -> RotationPlan.nextTimeOfDay(now, settings.timesOfDay, offset)
            } ?: return cancelAlarm()
        alarmManager().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(RotateWallpaperWorker.PERIODIC_NAME)
        cancelAlarm()
    }

    private fun cancelAlarm() = alarmManager().cancel(alarmIntent())

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun alarmIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TimeOfDayAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

package dev.fengine.wallpaper.schedule

import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.wallpaper.WallpaperApplier
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One rotation, whoever asks: the alarm (the main scheduled path, see RotationScheduler), the
 * periodic job kept as a backstop, the live engine on screen-on, or a person (Next photo, Show now,
 * Set active, Still, tile, widget). Scheduled callers change the photo only when a change is due
 * ([RotationPlan.due]), so several paths can fire without doubling up; a lock keeps two runs from
 * interleaving in one process.
 */
object Rotation {
    private const val TAG = "Rotation"
    private val lock = Mutex()

    /**
     * Retries after unreadable photos, inside the same run (they used to be new jobs, under job
     * quota).
     */
    private const val MAX_HOPS = 3

    sealed interface Outcome {
        data object NotDue : Outcome

        data object NothingToShow : Outcome

        data class Done(val failures: List<String>) : Outcome
    }

    fun isScheduled(source: String) =
        source == Ledger.Source.SCHEDULE ||
            source == Ledger.Source.ALARM ||
            source == Ledger.Source.WAKE

    suspend fun run(
        context: Context,
        source: String,
        pinned: Map<String, Int> = emptyMap(),
        forceStatic: Boolean = false,
    ): Outcome = lock.withLock {
        val app = context.applicationContext as FengineApp
        val store = app.settings
        // Photos found through the library permission cannot be read any more: drop them first.
        if (store.read().groups.any { it.withoutLibraryFinds() != it }) {
            store.update { s -> s.copy(groups = s.groups.map { it.withoutLibraryFinds() }) }
        }
        val first = store.read()
        val now = System.currentTimeMillis()
        if (isScheduled(source) && !first.enabled) return@withLock Outcome.NotDue
        if (
            isScheduled(source) &&
                !RotationPlan.due(
                    first.mode,
                    now,
                    first.lastRotationAt,
                    first.intervalMinutes,
                    first.timesOfDay,
                    offset(now),
                )
        ) {
            Log.i(TAG, "$source: not due")
            return@withLock Outcome.NotDue
        }
        val ledger = LedgerStore(app)
        val bucket = runCatching {
            (app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .appStandbyBucket
        }
            .getOrDefault(-1)
        val lateMs =
            if (isScheduled(source) && first.mode == Mode.INTERVAL)
                RotationPlan.lateness(now, first.lastRotationAt, first.intervalMinutes)
            else null
        val failures = mutableListOf<String>()
        var changed = false
        var pins = pinned
        for (hop in 0..MAX_HOPS) {
            val s = store.read()
            val pass =
                onePass(
                    app,
                    s,
                    source,
                    pins,
                    forceStatic,
                    ledger,
                    bucket,
                    lateMs.takeIf { hop == 0 },
                )
                    ?: return@withLock if (changed) Outcome.Done(failures)
                    else
                        Outcome.NothingToShow.also {
                            store.update { it.copy(lastResult = "Nothing to show yet") }
                        }
            failures += pass.failures
            changed = changed || pass.changed
            val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            store.update { cur ->
                cur.copy(
                    groups =
                        cur.groups
                            .map { g -> pass.cursors[g.id]?.let { g.copy(cursor = it) } ?: g }
                            .map { g ->
                                pass.unreadable
                                    .filter { it.first == g.id }
                                    .fold(g) { acc, (_, uri) -> acc.withoutPhoto(uri) }
                            },
                    lastResult =
                        if (pass.failures.isEmpty()) "Changed at $stamp"
                        else "Problem at $stamp: ${pass.failures.joinToString("; ")}",
                    lastRotationAt =
                        if (pass.changed) System.currentTimeMillis() else cur.lastRotationAt,
                )
            }
            // A dropped photo leaves its screen on the old picture: try the next one right away.
            if (pass.unreadable.isEmpty()) break
            pins = emptyMap()
        }
        // Whoever changed the photo (backstop job, engine on screen-on, a person), the alarm now
        // counts
        // from this change; otherwise it would fire at its old time only to find nothing due.
        if (changed) RotationScheduler(app).armNextAlarm(store.read())
        Outcome.Done(failures)
    }

    private class Pass(
        val cursors: Map<String, Int>,
        val unreadable: List<Pair<String, String>>,
        val failures: List<String>,
        val changed: Boolean,
    )

    private fun onePass(
        context: Context,
        settings: Settings,
        source: String,
        pinned: Map<String, Int>,
        forceStatic: Boolean,
        ledger: LedgerStore,
        bucket: Int,
        lateMs: Long?,
    ): Pass? {
        val live =
            if (forceStatic) LiveWallpaper.State(false, false, false, false)
            else LiveWallpaper.state(context)
        Log.i(TAG, "$source: live $live")
        // Screens the live engine draws itself only need the cursor to move; it watches the
        // settings.
        val plan =
            RotationPlan.advance(
                settings.homeGroupId,
                settings.lockGroupId,
                photos = { id -> settings.group(id)?.rotation.orEmpty() },
                cursor = { id -> settings.group(id)?.cursor ?: -1 },
                shuffle = settings.shuffle,
                liveHome = live.innerHome,
                liveLock = live.innerLock,
                pinned = pinned,
                coverGroupId = settings.coverGroupId,
                coverLiveHome = live.coverHome,
                coverLiveLock = live.coverLock,
            )
        if (plan.cursors.isEmpty()) return null
        val applier = WallpaperApplier(context)
        val unreadable = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        var changed = false
        for ((id, index) in plan.cursors) if (plan.writes.none { it.groupId == id }) {
            Log.i(TAG, "${settings.group(id)?.name}: photo ${index + 1} -> live engine")
            ledger.record(Ledger.Event.Rotation(source, 0, 0, "live", bucket, lateMs))
            changed = true
        }
        for (write in plan.writes) {
            val group = settings.group(write.groupId) ?: continue
            when (val outcome = applier.apply(write.uri.toUri(), write.which, write.panels)) {
                is WallpaperApplier.Result.Applied -> {
                    Log.i(
                        TAG,
                        "${group.name}: photo ${write.index + 1} -> which=${write.which} panels=${write.panels}, ids ${outcome.wallpaperIds}",
                    )
                    ledger.record(
                        Ledger.Event.Rotation(
                            source,
                            write.which,
                            write.panels,
                            "applied",
                            bucket,
                            lateMs,
                        )
                    )
                    changed = true
                }
                is WallpaperApplier.Result.Unreadable -> {
                    val why = "${outcome.reason} ${Uri.parse(write.uri).authority.orEmpty()}".trim()
                    Log.w(
                        TAG,
                        "${group.name}: photo ${write.index + 1} unreadable ($why); dropped from the group",
                    )
                    ledger.record(
                        Ledger.Event.Rotation(
                            source,
                            write.which,
                            write.panels,
                            "unreadable",
                            bucket,
                            lateMs,
                            why,
                        )
                    )
                    unreadable += write.groupId to write.uri
                }
                is WallpaperApplier.Result.Failed -> {
                    ledger.record(
                        Ledger.Event.Rotation(
                            source,
                            write.which,
                            write.panels,
                            "failed",
                            bucket,
                            lateMs,
                            outcome.reason.take(80),
                        )
                    )
                    Log.w(TAG, "${group.name}: ${outcome.reason}")
                    failures += "${group.name}: ${outcome.reason}"
                }
            }
        }
        return Pass(plan.cursors, unreadable, failures, changed)
    }

    private fun offset(now: Long) = TimeZone.getDefault().getOffset(now).toLong()
}

package dev.fengine.wallpaper.data

import org.json.JSONObject

/**
 * The local ledger: what the app did and how the engine behaved, kept on the phone only and
 * exported by the owner (docs/product/2026-10-15-product-review.md, item 2). One JSON object per
 * line. Pure: encoding, decoding and the summary are property-tested; `LedgerStore` writes the
 * file.
 */
object Ledger {
    sealed interface Event {
        /** A rotation attempt for one write (or a live screen that only advanced its cursor). */
        data class Rotation(
            val source: String,
            val which: Int,
            val panels: Int,
            val result: String,
            /** App standby bucket when it ran (10 active ... 50 never); -1 unknown. */
            val bucket: Int = -1,
            /** How late against the due time, for scheduled changes; null when not scheduled. */
            val lateMs: Long? = null,
            /** Why a photo could not be used (exception class and URI authority, never the URI). */
            val reason: String? = null,
            /**
             * The run this write belongs to (its start time): one change can write several screens
             * and retry after an unreadable photo. Null on lines from before 0.5.
             */
            val run: Long? = null,
        ) : Event

        /** A shown screen got its first engine frame after [latencyMs]. */
        data class FirstFrame(val latencyMs: Long) : Event

        /** A shown screen was hidden again before any frame: a black screen for [shownMs]. */
        data class NoFrame(val shownMs: Long) : Event

        /** A screen was hidden while its last frame was half of a cross-fade. */
        data object AbandonedBlend : Event

        /** The screen saver started; [verdict] is DreamGate's answer. */
        data class ScreenSaver(val verdict: String, val plugged: Int) : Event

        /**
         * The app's process ended earlier, as Android recorded it (`ApplicationExitInfo`, Android
         * 11+): [reason] from [exitReason], [importance] at the time (100 foreground ... 400
         * cached). The entry's time is the exit's own time. The live engine shares the process, so
         * two exits within 10 s, or one force stop, make Android revert Motion to its built-in
         * wallpaper (WallpaperManagerService; reproduced on Android 16, 2026-10-25).
         */
        data class Exit(val reason: String, val importance: Int) : Event

        /** An uncaught exception: [site] is the exception class and the app's first frame. */
        data class Crash(val site: String) : Event

        /** Motion was on and is now off without the owner choosing Still (see MotionWatch). */
        data object MotionLost : Event
    }

    object Source {
        const val SCHEDULE = "schedule"
        const val ALARM = "alarm"
        const val WAKE = "wake"
        const val NEXT = "next"
        const val SHOW_NOW = "show-now"
        const val SET_ACTIVE = "set-active"
        const val STILL = "still"
        const val TILE = "tile"
        const val WIDGET = "widget"
        const val TAP = "tap"
        const val THEME = "theme"
        val manual = setOf(NEXT, SHOW_NOW, SET_ACTIVE, TILE, WIDGET, TAP)
    }

    data class Entry(val atMs: Long, val event: Event)

    fun encode(e: Entry): String {
        val o = JSONObject().put("t", e.atMs)
        when (val ev = e.event) {
            is Event.Rotation ->
                o.put("type", "rotation")
                    .put("source", ev.source)
                    .put("which", ev.which)
                    .put("panels", ev.panels)
                    .put("result", ev.result)
                    .apply {
                        if (ev.bucket >= 0) put("bucket", ev.bucket)
                        ev.lateMs?.let { put("late_ms", it) }
                        ev.reason?.let { put("reason", it) }
                        ev.run?.let { put("run", it) }
                    }
            is Event.FirstFrame -> o.put("type", "first_frame").put("ms", ev.latencyMs)
            is Event.NoFrame -> o.put("type", "no_frame").put("ms", ev.shownMs)
            Event.AbandonedBlend -> o.put("type", "abandoned_blend")
            is Event.ScreenSaver ->
                o.put("type", "screen_saver").put("verdict", ev.verdict).put("plugged", ev.plugged)
            is Event.Exit ->
                o.put("type", "exit").put("reason", ev.reason).put("importance", ev.importance)
            is Event.Crash -> o.put("type", "crash").put("site", ev.site)
            Event.MotionLost -> o.put("type", "motion_lost")
        }
        return o.toString()
    }

    /**
     * Null for a line this version does not understand (newer type, damaged line): skipped, never
     * fatal.
     */
    fun decode(line: String): Entry? = runCatching {
        val o = JSONObject(line)
        val t = o.getLong("t")
        val ev: Event =
            when (o.getString("type")) {
                "rotation" ->
                    Event.Rotation(
                        o.getString("source"),
                        o.getInt("which"),
                        o.getInt("panels"),
                        o.getString("result"),
                        bucket = o.optInt("bucket", -1),
                        lateMs = if (o.has("late_ms")) o.getLong("late_ms") else null,
                        reason = if (o.has("reason")) o.getString("reason") else null,
                        run = if (o.has("run")) o.getLong("run") else null,
                    )
                "first_frame" -> Event.FirstFrame(o.getLong("ms"))
                "no_frame" -> Event.NoFrame(o.getLong("ms"))
                "abandoned_blend" -> Event.AbandonedBlend
                "screen_saver" -> Event.ScreenSaver(o.getString("verdict"), o.getInt("plugged"))
                "exit" -> Event.Exit(o.getString("reason"), o.getInt("importance"))
                "crash" -> Event.Crash(o.getString("site"))
                "motion_lost" -> Event.MotionLost
                else -> return null
            }
        Entry(t, ev)
    }
        .getOrNull()

    data class Summary(
        val rotations: Int,
        val failed: Int,
        /** Photos that could no longer be read and were removed from their group. */
        val removed: Int,
        val manual: Int,
        val firstFrames: Int,
        val firstFrameP50: Long?,
        val firstFrameP95: Long?,
        val noFrame: Int,
        val abandonedBlends: Int,
        val screenSaver: Map<String, Int>,
        /** Process exits by reason ("crash", "anr", ...). */
        val exits: Map<String, Int> = emptyMap(),
        val motionLost: Int = 0,
    )

    /**
     * The `ApplicationExitInfo` reasons worth a line: the ones that say the app failed or was
     * stopped. Plain exits, cached-process kills and package updates are routine and skipped.
     */
    fun exitReason(code: Int, sdk: Int = 34): String? =
        when (code) {
            // Before Android 14 an app update also reads as "user requested".
            10 -> if (sdk >= 34) "user_requested" else null
            3 -> "low_memory"
            4 -> "crash"
            5 -> "native_crash"
            6 -> "anr"
            7 -> "init_failure"
            9 -> "excessive_resources"
            11 -> "user_stopped"
            12 -> "dependency_died"
            14 -> "freezer"
            17 -> "memory_limiter"
            else -> null
        }

    /**
     * Exits not yet in the ledger: those after [lastRecordedMs] with a reason worth a line, oldest
     * first. [exits] are (timestamp, reason code, importance) as Android returns them.
     */
    fun newExits(
        exits: List<Triple<Long, Int, Int>>,
        lastRecordedMs: Long?,
        sdk: Int = 34,
    ): List<Entry> =
        exits
            .filter { (t, _, _) -> lastRecordedMs == null || t > lastRecordedMs }
            .mapNotNull { (t, code, imp) ->
                exitReason(code, sdk)?.let { Entry(t, Event.Exit(it, imp)) }
            }
            .sortedBy { it.atMs }

    private val libraryFrames =
        listOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "libcore.",
            "dalvik.",
            "android.",
            "androidx.",
            "com.android.",
            "kotlin.",
            "kotlinx.",
            "com.google.",
        )

    /**
     * Where a crash came from without any message text (which can hold a URI): the root cause's
     * class and the first frame outside the platform and libraries, from [frames] as
     * "class.method(File:line)". Release builds are minified, so that frame is the app's own under
     * an obfuscated name; the release mapping file turns it back.
     */
    fun crashSite(rootClass: String, frames: List<String>): String {
        // R8 also renames library classes, so one "ours" frame can be a renamed library frame:
        // keep the first three candidates for the mapping file to sort out.
        val ours = frames.filter { f -> libraryFrames.none { f.startsWith(it) } }.take(3)
        return if (ours.isEmpty()) rootClass else "$rootClass at ${ours.joinToString(" < ")}"
    }

    /**
     * A screen-saver start is worth a line when it says something new: the first one, a different
     * verdict or charger, or the same again after [quietMs]. A long charge on a cable is one line.
     */
    fun shouldRecordScreenSaver(
        last: Entry?,
        verdict: String,
        plugged: Int,
        now: Long,
        quietMs: Long = 6 * 3600_000L,
    ): Boolean {
        val prev = last?.event as? Event.ScreenSaver ?: return true
        return prev.verdict != verdict || prev.plugged != plugged || now - last.atMs >= quietMs
    }

    /** Nearest-rank percentile of [sorted] (ascending), [p] in 1..100; null when empty. */
    fun percentile(sorted: List<Long>, p: Int): Long? {
        if (sorted.isEmpty()) return null
        // Integer ceiling: 0.28 * 25 is 7.000000000000001 in floating point, which ranked one too
        // high.
        val rank = ((p.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** What happened in [fromMs, toMs]. */
    fun summarize(entries: List<Entry>, fromMs: Long, toMs: Long): Summary {
        val inWindow = entries.filter { it.atMs in fromMs..toMs }.map { it.event }
        val rot = inWindow.filterIsInstance<Event.Rotation>()
        // One change per run (several screens and retries write several lines); old lines count
        // alone.
        val runs = rot.withIndex().groupBy({ (i, r) -> r.run ?: -(i + 1L) }, { it.value }).values
        val ff = inWindow.filterIsInstance<Event.FirstFrame>().map { it.latencyMs }.sorted()
        return Summary(
            rotations = runs.size,
            failed = runs.count { lines -> lines.any { it.result == "failed" } },
            removed = rot.count { it.result == "unreadable" },
            manual = runs.count { lines -> lines.first().source in Source.manual },
            firstFrames = ff.size,
            firstFrameP50 = percentile(ff, 50),
            firstFrameP95 = percentile(ff, 95),
            noFrame = inWindow.count { it is Event.NoFrame },
            abandonedBlends = inWindow.count { it == Event.AbandonedBlend },
            screenSaver =
                inWindow
                    .filterIsInstance<Event.ScreenSaver>()
                    .groupingBy { it.verdict }
                    .eachCount(),
            exits = inWindow.filterIsInstance<Event.Exit>().groupingBy { it.reason }.eachCount(),
            motionLost = inWindow.count { it == Event.MotionLost },
        )
    }

    /** One sentence for the app: the numbers that would have shown this cycle's bugs. */
    fun sentence(s: Summary): String = buildString {
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        append(n(s.rotations, "change", "changes"))
        if (s.failed > 0) append(", ${s.failed} failed")
        if (s.removed > 0)
            append(
                ", ${if (s.removed == 1) "1 photo" else "${s.removed} photos"} could no longer be read and ${if (s.removed == 1) "was" else "were"} removed"
            )
        if (s.manual > 0) append(", ${s.manual} by hand")
        if (s.firstFrames > 0)
            append(
                ". Motion: first frame ${s.firstFrameP50} ms typical, ${s.firstFrameP95} ms slowest 5%"
            )
        if (s.noFrame > 0) append(", " + n(s.noFrame, "blank screen", "blank screens"))
        if (s.abandonedBlends > 0)
            append(", " + n(s.abandonedBlends, "interrupted fade", "interrupted fades"))
        if (s.screenSaver.isNotEmpty())
            append(
                ". Screen saver: " +
                    s.screenSaver.entries
                        .sortedBy { it.key }
                        .joinToString { "${it.key.lowercase().replace('_', ' ')} ${it.value}" }
            )
        if (s.motionLost > 0)
            append(
                ". Motion stopped " + (if (s.motionLost == 1) "once" else "${s.motionLost} times")
            )
        if (s.exits.isNotEmpty())
            append(
                ". App stopped: " +
                    s.exits.entries
                        .sortedBy { it.key }
                        .joinToString { "${it.key.replace('_', ' ')} ${it.value}" }
            )
        append(".")
    }
}

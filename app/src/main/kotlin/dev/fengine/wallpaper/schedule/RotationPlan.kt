package dev.fengine.wallpaper.schedule

import kotlin.random.Random

/** Pure scheduling arithmetic, kept free of Android types so it is unit-testable on the JVM. */
object RotationPlan {
    const val FLAG_SYSTEM = 1
    const val FLAG_LOCK = 2

    /** One setBitmap call: which group feeds which screen flags. */
    data class Step(val groupId: String, val which: Int)

    /**
     * Turn the per-screen group assignment into wallpaper calls. The same group on both screens is
     * one call with both flags so the system treats the image as shared; different groups get one
     * call each. Screens without a group, or whose group is empty, are left alone.
     */
    fun steps(
        homeGroupId: String?,
        lockGroupId: String?,
        isEmpty: (String) -> Boolean,
    ): List<Step> {
        val home = homeGroupId?.takeUnless(isEmpty)
        val lock = lockGroupId?.takeUnless(isEmpty)
        return when {
            home != null && home == lock -> listOf(Step(home, FLAG_SYSTEM or FLAG_LOCK))
            else ->
                listOfNotNull(
                    home?.let { Step(it, FLAG_SYSTEM) },
                    lock?.let { Step(it, FLAG_LOCK) },
                )
        }
    }

    /** Index of the next photo to show. Returns -1 when there is nothing to show. */
    /**
     * The flags of [which] that still need a static setBitmap when the live engine draws the home
     * ([liveHome]) or lock ([liveLock]) screen itself. The engine reads the group's cursor, so the
     * worker still advances it; this only decides whether a bitmap is written for that step.
     */
    fun staticFlags(which: Int, liveHome: Boolean, liveLock: Boolean): Int {
        var out = which
        if (liveHome) out = out and FLAG_SYSTEM.inv()
        if (liveLock) out = out and FLAG_LOCK.inv()
        return out
    }

    /**
     * One static write the worker must make: [uri] to the screens in [which]. [panels] is 0 for
     * every panel, or Samsung display bits (4 inner, 16 cover) when a foldable's panels show
     * different groups.
     */
    data class Write(
        val groupId: String,
        val index: Int,
        val uri: String,
        val which: Int,
        val panels: Int = 0,
    )

    /** Everything one rotation decides: the writes to make and the new cursor per group. */
    data class Advance(val writes: List<Write>, val cursors: Map<String, Int>)

    /**
     * One rotation, decided in full: each group that feeds a screen advances its cursor exactly
     * once (also when it feeds both screens), the live engine's screens get no write, and the
     * remaining screens get the group's new photo.
     */
    fun advance(
        homeGroupId: String?,
        lockGroupId: String?,
        photos: (String) -> List<String>,
        cursor: (String) -> Int,
        shuffle: Boolean,
        liveHome: Boolean,
        liveLock: Boolean,
        random: Random = Random.Default,
        /** Indexes to show instead of advancing ("show this one now"). */
        pinned: Map<String, Int> = emptyMap(),
        /**
         * The cover screen's own group on a foldable with Samsung display bits, or null for the
         * same photo.
         */
        coverGroupId: String? = null,
        /**
         * The cover panel's live state on a foldable with Samsung display bits; the inner panel's
         * elsewhere.
         */
        coverLiveHome: Boolean = liveHome,
        coverLiveLock: Boolean = liveLock,
        /**
         * Photos to prefer for this change (dark photos while the phone is dark), asked lazily in
         * play order; null for no preference.
         */
        prefer: ((String) -> Boolean)? = null,
    ): Advance {
        val steps = steps(homeGroupId, lockGroupId) { photos(it).isEmpty() }
        val cursors = LinkedHashMap<String, Int>()
        val writes = ArrayList<Write>()
        fun indexFor(id: String): Int {
            val pool = photos(id)
            return cursors.getOrPut(id) {
                pinned[id]?.takeIf { it in pool.indices }
                    ?: if (prefer == null) nextIndex(pool.size, cursor(id), shuffle, random)
                    else nextPreferred(pool, cursor(id), shuffle, random, prefer)
            }
        }
        val cover = coverGroupId?.takeIf { photos(it).isNotEmpty() }
        for (step in steps) {
            val index = indexFor(step.groupId)
            val innerWhich = staticFlags(step.which, liveHome, liveLock)
            val coverWhich = staticFlags(step.which, coverLiveHome, coverLiveLock)
            val coverSrc = cover ?: step.groupId
            if (coverSrc == step.groupId && innerWhich == coverWhich) {
                if (innerWhich != 0)
                    writes += Write(step.groupId, index, photos(step.groupId)[index], innerWhich)
            } else {
                // The panels differ: inner from this step's group, cover from the cover group or
                // this group with the flags the engine does not draw there.
                if (innerWhich != 0)
                    writes +=
                        Write(
                            step.groupId,
                            index,
                            photos(step.groupId)[index],
                            innerWhich,
                            panels = PANEL_INNER,
                        )
                if (coverWhich != 0) {
                    val ci = indexFor(coverSrc)
                    writes +=
                        Write(coverSrc, ci, photos(coverSrc)[ci], coverWhich, panels = PANEL_COVER)
                }
            }
        }
        return Advance(writes, cursors)
    }

    /** Samsung display bits, see `SamsungDisplays`. */
    const val PANEL_INNER = 4
    const val PANEL_COVER = 16

    fun nextIndex(count: Int, cursor: Int, shuffle: Boolean, random: Random = Random.Default): Int {
        if (count <= 0) return -1
        if (!shuffle || count == 1) return (cursor + 1).mod(count)
        // Avoid repeating the current photo when there is a choice.
        var pick = random.nextInt(count - 1)
        if (cursor in 0 until count && pick >= cursor) pick += 1
        return pick
    }

    /**
     * The next photo that [prefer] accepts, in play order: forward from [cursor] in group order, or
     * a random order with the current photo last when shuffled. With none accepted, the plain next
     * photo ([nextIndex]). [prefer] is asked lazily, at most once per photo, stopping at the first
     * yes, because an answer may cost a decode.
     */
    fun nextPreferred(
        pool: List<String>,
        cursor: Int,
        shuffle: Boolean,
        random: Random,
        prefer: (String) -> Boolean,
    ): Int {
        val n = pool.size
        if (n <= 0) return -1
        val current = cursor.takeIf { it in 0 until n }
        val others = (0 until n).filter { it != current }
        val order =
            (if (shuffle) others.shuffled(random)
            else others.sortedBy { (it - cursor - 1).mod(n) }) + listOfNotNull(current)
        return order.firstOrNull { prefer(pool[it]) } ?: nextIndex(n, cursor, shuffle, random)
    }

    /**
     * Shortest wait before an overdue interval alarm fires, so a missed slot never fires in a
     * burst.
     */
    const val MIN_ALARM_DELAY_MS = 60_000L

    /** A scheduled change may run this early (alarm batching); earlier than this it is skipped. */
    const val EARLY_TOLERANCE = 0.9

    /**
     * When the next interval change is due: one interval after the last change, or soon if that
     * moment already passed (a missed slot), or one interval from now before the first change.
     */
    fun nextIntervalAt(nowMs: Long, lastChangeMs: Long, intervalMinutes: Int): Long {
        val interval = intervalMinutes.coerceAtLeast(1) * 60_000L
        if (lastChangeMs <= 0) return nowMs + interval
        return maxOf(lastChangeMs + interval, nowMs + MIN_ALARM_DELAY_MS)
    }

    /**
     * Whether a scheduled change should happen now. Several paths can ask (the alarm, the periodic
     * job kept as a backstop, the live engine on screen-on); only the first one that is due changes
     * the photo, so they never double up.
     */
    fun isDue(nowMs: Long, lastChangeMs: Long, intervalMinutes: Int): Boolean {
        if (lastChangeMs <= 0) return true
        val interval = intervalMinutes.coerceAtLeast(1) * 60_000L
        return nowMs - lastChangeMs >= (interval * EARLY_TOLERANCE).toLong()
    }

    /**
     * Due for either rhythm: an interval elapsed (within [EARLY_TOLERANCE]), or a set time passed
     * since the last change.
     */
    fun due(
        mode: dev.fengine.wallpaper.data.Mode,
        nowMs: Long,
        lastChangeMs: Long,
        intervalMinutes: Int,
        slots: List<Int>,
        zoneOffsetMillis: Long,
    ): Boolean =
        when (mode) {
            dev.fengine.wallpaper.data.Mode.INTERVAL -> isDue(nowMs, lastChangeMs, intervalMinutes)
            dev.fengine.wallpaper.data.Mode.TIME_OF_DAY ->
                lastChangeMs <= 0 ||
                    (nextTimeOfDay(lastChangeMs, slots, zoneOffsetMillis)?.let { it <= nowMs }
                        ?: false)
        }

    /** How late a change ran against its due time (negative when early, within tolerance). */
    fun lateness(nowMs: Long, lastChangeMs: Long, intervalMinutes: Int): Long =
        if (lastChangeMs <= 0) 0
        else nowMs - (lastChangeMs + intervalMinutes.coerceAtLeast(1) * 60_000L)

    /**
     * Next trigger as epoch millis for a list of minutes-after-midnight slots in
     * [zoneOffsetMillis]. Uses a plain civil-day model: good enough for wallpaper, and DST shifts
     * self-correct because the receiver reschedules from the real clock after each firing.
     */
    fun nextTimeOfDay(
        nowEpochMillis: Long,
        slotsMinutes: List<Int>,
        zoneOffsetMillis: Long,
    ): Long? {
        if (slotsMinutes.isEmpty()) return null
        val day = 24L * 60 * 60 * 1000
        val local = nowEpochMillis + zoneOffsetMillis
        val midnight = local - local.mod(day)
        // Slots outside a day (a corrupt store, a future import) wrap instead of scheduling into
        // the past.
        val sorted = slotsMinutes.map { it.mod(24 * 60) }.distinct().sorted()
        val todayCandidate = sorted.map { midnight + it * 60_000L }.firstOrNull { it > local }
        val next = todayCandidate ?: (midnight + day + sorted.first() * 60_000L)
        return next - zoneOffsetMillis
    }
}

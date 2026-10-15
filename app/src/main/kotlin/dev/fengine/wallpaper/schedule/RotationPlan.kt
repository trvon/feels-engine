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
    fun steps(homeGroupId: String?, lockGroupId: String?, isEmpty: (String) -> Boolean): List<Step> {
        val home = homeGroupId?.takeUnless(isEmpty)
        val lock = lockGroupId?.takeUnless(isEmpty)
        return when {
            home != null && home == lock -> listOf(Step(home, FLAG_SYSTEM or FLAG_LOCK))
            else -> listOfNotNull(home?.let { Step(it, FLAG_SYSTEM) }, lock?.let { Step(it, FLAG_LOCK) })
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
    data class Write(val groupId: String, val index: Int, val uri: String, val which: Int, val panels: Int = 0)

    /** Everything one rotation decides: the writes to make and the new cursor per group. */
    data class Advance(val writes: List<Write>, val cursors: Map<String, Int>)

    /**
     * One rotation, decided in full: each group that feeds a screen advances its cursor exactly
     * once (also when it feeds both screens), the live engine's screens get no write, and the
     * remaining screens get the group's new photo.
     */
    fun advance(
        homeGroupId: String?, lockGroupId: String?,
        photos: (String) -> List<String>, cursor: (String) -> Int,
        shuffle: Boolean, liveHome: Boolean, liveLock: Boolean, random: Random = Random.Default,
        /** Indexes to show instead of advancing ("show this one now"). */
        pinned: Map<String, Int> = emptyMap(),
        /** The cover screen's own group on a foldable with Samsung display bits, or null for the same photo. */
        coverGroupId: String? = null,
        /** True where the live engine draws the inner panel only (One UI): the cover panel is always static. */
        coverStatic: Boolean = false,
    ): Advance {
        val steps = steps(homeGroupId, lockGroupId) { photos(it).isEmpty() }
        val cursors = LinkedHashMap<String, Int>()
        val writes = ArrayList<Write>()
        fun indexFor(id: String): Int {
            val pool = photos(id)
            return cursors.getOrPut(id) {
                pinned[id]?.takeIf { it in pool.indices } ?: nextIndex(pool.size, cursor(id), shuffle, random)
            }
        }
        val cover = coverGroupId?.takeIf { photos(it).isNotEmpty() }
        for (step in steps) {
            val index = indexFor(step.groupId)
            val innerWhich = staticFlags(step.which, liveHome, liveLock)
            val coverWhich = if (coverStatic) step.which else innerWhich
            val coverSrc = cover ?: step.groupId
            if (coverSrc == step.groupId && innerWhich == coverWhich) {
                if (innerWhich != 0) writes += Write(step.groupId, index, photos(step.groupId)[index], innerWhich)
            } else {
                // The panels differ: inner from this step's group, cover from the cover group or
                // this group with the flags the engine does not draw there.
                if (innerWhich != 0) writes += Write(step.groupId, index, photos(step.groupId)[index], innerWhich, panels = PANEL_INNER)
                if (coverWhich != 0) {
                    val ci = indexFor(coverSrc)
                    writes += Write(coverSrc, ci, photos(coverSrc)[ci], coverWhich, panels = PANEL_COVER)
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
     * Next trigger as epoch millis for a list of minutes-after-midnight slots in [zoneOffsetMillis].
     * Uses a plain civil-day model: good enough for wallpaper, and DST shifts self-correct because
     * the receiver reschedules from the real clock after each firing.
     */
    fun nextTimeOfDay(nowEpochMillis: Long, slotsMinutes: List<Int>, zoneOffsetMillis: Long): Long? {
        if (slotsMinutes.isEmpty()) return null
        val day = 24L * 60 * 60 * 1000
        val local = nowEpochMillis + zoneOffsetMillis
        val midnight = local - local.mod(day)
        // Slots outside a day (a corrupt store, a future import) wrap instead of scheduling into the past.
        val sorted = slotsMinutes.map { it.mod(24 * 60) }.distinct().sorted()
        val todayCandidate = sorted.map { midnight + it * 60_000L }.firstOrNull { it > local }
        val next = todayCandidate ?: (midnight + day + sorted.first() * 60_000L)
        return next - zoneOffsetMillis
    }
}

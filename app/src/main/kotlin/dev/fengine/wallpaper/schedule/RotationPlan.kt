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

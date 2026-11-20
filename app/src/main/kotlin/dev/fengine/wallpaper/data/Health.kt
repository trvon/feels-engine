package dev.fengine.wallpaper.data

import dev.fengine.wallpaper.schedule.RotationPlan
import java.time.ZoneId

/**
 * The one line Home adds under its headline when something is wrong, with the one fix; nothing when
 * all is well (docs/product/2026-11-20-product-review.md, item 4). The headline states the
 * schedule; this says what actually happened. Pure: property-tested.
 */
object Health {
    enum class Fix {
        /** Android is holding the app back: open its unused-app / battery settings. */
        APP_SLEEP,
        /** Photos could not be read: open their group. */
        GROUP,
    }

    data class Line(val text: String, val fix: Fix, val groupId: String? = null)

    /** A change this late against its interval counts as stalled. */
    const val LATE_FACTOR = 3

    /** A set time missed by more than this counts as stalled. */
    const val SLOT_GRACE_MS = 3_600_000L

    /**
     * When the last change is overdue: [Settings.lastRotationAt] is more than [LATE_FACTOR]
     * intervals ago, or a set time passed more than [SLOT_GRACE_MS] ago with no change. Null when
     * rotation is off or has never run.
     */
    fun overdue(s: Settings, now: Long, zone: ZoneId): Boolean {
        if (!s.enabled || s.lastRotationAt <= 0) return false
        return when (s.mode) {
            Mode.INTERVAL -> {
                val interval = s.intervalMinutes.coerceAtLeast(Settings.MIN_INTERVAL_MINUTES)
                now - s.lastRotationAt > LATE_FACTOR * interval * 60_000L
            }
            Mode.TIME_OF_DAY -> {
                val next = RotationPlan.nextTimeOfDay(s.lastRotationAt, s.timesOfDay, zone)
                next != null && now - next > SLOT_GRACE_MS
            }
        }
    }

    /** Photos on a screen's group that are set aside as unreadable, and the first such group. */
    fun unreadable(s: Settings): Pair<Int, String?> {
        val shown =
            listOfNotNull(s.homeGroup, s.lockGroup, s.coverGroup, s.dreamGroup).distinctBy { it.id }
        val ids = shown.flatMap { g -> g.unreadable.map(PhotoId::identity) }.toSet()
        return ids.size to shown.firstOrNull { it.unreadable.isNotEmpty() }?.id
    }

    /**
     * The worst single fact, or null when healthy. [ago] words the time since the last change ("2
     * days ago"); [restricted] says Android's unused-app restrictions are on for the app.
     */
    fun line(
        s: Settings,
        now: Long,
        zone: ZoneId,
        ago: (Long) -> String,
        restricted: Boolean = false,
    ): Line? {
        if (overdue(s, now, zone))
            return Line(
                "Last change ${ago(s.lastRotationAt)}. " +
                    (if (restricted) "Android is pausing the app while it goes unused."
                    else "Android may be holding the app back.") +
                    " Opening the app's settings and allowing it to run lets changes keep time.",
                Fix.APP_SLEEP,
            )
        val (n, group) = unreadable(s)
        if (n > 0)
            return Line(
                (if (n == 1) "1 photo could not be read" else "$n photos could not be read") +
                    " and ${if (n == 1) "is" else "are"} left out. Pick ${if (n == 1) "it" else "them"}" +
                    " again from the group to bring ${if (n == 1) "it" else "them"} back.",
                Fix.GROUP,
                group,
            )
        return null
    }
}

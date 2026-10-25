package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.schedule.RotationPlan
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * The scheduled change: the next alarm is always ahead, never further than one interval past the
 * last change unless that moment was missed, and several callers (alarm, backstop job, engine on
 * screen-on) never change the photo twice for one slot.
 */
class SchedulingProperties {
    private val day = 24L * 3600_000

    @Property(tries = 3000)
    fun nextIntervalAlarmIsAheadAndOnTime(
        @ForAll @LongRange(min = 1, max = 4_000_000_000_000) now: Long,
        @ForAll @LongRange(min = 0, max = 400_000_000) sinceLast: Long,
        @ForAll @IntRange(min = 15, max = 1440) interval: Int,
        @ForAll neverChanged: Boolean,
    ) {
        val last = if (neverChanged) 0L else (now - sinceLast).coerceAtLeast(1)
        val at = RotationPlan.nextIntervalAt(now, last, interval)
        val ms = interval * 60_000L
        assertTrue("alarm not in the future", at > now)
        if (last <= 0) assertEquals(now + ms, at)
        else if (last + ms > now + RotationPlan.MIN_ALARM_DELAY_MS)
            assertEquals("on time", last + ms, at)
        else
            assertEquals(
                "a missed slot fires soon, once",
                now + RotationPlan.MIN_ALARM_DELAY_MS,
                at,
            )
    }

    @Property(tries = 3000)
    fun intervalDueMatchesTheTolerance(
        @ForAll @LongRange(min = 1, max = 4_000_000_000_000) last: Long,
        @ForAll @LongRange(min = 0, max = 200_000_000) elapsed: Long,
        @ForAll @IntRange(min = 15, max = 1440) interval: Int,
    ) {
        val due = RotationPlan.isDue(last + elapsed, last, interval)
        assertEquals(elapsed >= (interval * 60_000L * RotationPlan.EARLY_TOLERANCE).toLong(), due)
        // The alarm's own time is always due when it fires on time.
        assertTrue(
            RotationPlan.isDue(RotationPlan.nextIntervalAt(last, last, interval), last, interval)
        )
    }

    /** Callers asking again right after a change see "not due": one slot, one change. */
    @Property(tries = 2000)
    fun aSecondCallerRightAfterAChangeIsNotDue(
        @ForAll @LongRange(min = 1, max = 4_000_000_000_000) changedAt: Long,
        @ForAll @LongRange(min = 0, max = 600_000) callerDelay: Long,
        @ForAll @IntRange(min = 15, max = 1440) interval: Int,
        @ForAll("modes") mode: Mode,
        @ForAll("slots") slots: List<Int>,
    ) {
        if (mode == Mode.TIME_OF_DAY && slots.isEmpty()) return
        val offset = 0L
        val now = changedAt + callerDelay
        // A set time between the change and the caller makes a genuine new slot; skip those cases.
        val nextSlot = RotationPlan.nextTimeOfDay(changedAt, slots, offset)
        if (mode == Mode.TIME_OF_DAY && nextSlot != null && nextSlot <= now) return
        assertFalse(RotationPlan.due(mode, now, changedAt, interval, slots, offset))
    }

    @Property(tries = 2000)
    fun timeOfDayIsDueOnceASlotPassed(
        @ForAll @LongRange(min = 1, max = 4_000_000_000_000) last: Long,
        @ForAll @LongRange(min = 0, max = 3 * 24 * 3600_000L) elapsed: Long,
        @ForAll("slots") slots: List<Int>,
    ) {
        if (slots.isEmpty()) return
        val next = RotationPlan.nextTimeOfDay(last, slots, 0)!!
        assertEquals(
            last + elapsed >= next,
            RotationPlan.due(Mode.TIME_OF_DAY, last + elapsed, last, 60, slots, 0),
        )
        assertTrue("a slot comes within a day", next - last <= day)
    }

    @Property(tries = 500)
    fun neverChangedIsAlwaysDue(
        @ForAll("modes") mode: Mode,
        @ForAll @LongRange(min = 1, max = 4_000_000_000_000) now: Long,
    ) {
        assertTrue(RotationPlan.due(mode, now, 0, 60, listOf(420), 0))
    }

    @Provide fun modes(): Arbitrary<Mode> = Arbitraries.of(*Mode.entries.toTypedArray())

    @Provide
    fun slots(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 24 * 60 - 1).list().ofMaxSize(4)
}

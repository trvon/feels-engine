package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.schedule.RotationPlan
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

/** Invariants of the scheduling arithmetic. */
class RotationPlanProperties {

    @Property(tries = 2000)
    fun nextIndexIsInRangeAndNeverRepeatsWhenShuffling(
        @ForAll @IntRange(min = 0, max = 500) count: Int,
        @ForAll @IntRange(min = -5, max = 600) cursor: Int,
        @ForAll shuffle: Boolean,
        @ForAll seed: Long,
    ) {
        val next = RotationPlan.nextIndex(count, cursor, shuffle, Random(seed))
        if (count == 0) assertEquals(-1, next)
        else {
            assertTrue("next $next out of range for count $count", next in 0 until count)
            if (shuffle && count > 1 && cursor in 0 until count) assertTrue("shuffle repeated $cursor", next != cursor)
            if (!shuffle) assertEquals((cursor + 1).mod(count), next)
        }
    }

    @Property(tries = 2000)
    fun nextTimeOfDayIsStrictlyInTheFutureAndOnASlot(
        @ForAll @LongRange(min = 0, max = 4_102_444_800_000L) now: Long,
        @ForAll("slots") slots: List<Int>,
        @ForAll @IntRange(min = -14 * 60, max = 14 * 60) offsetMinutes: Int,
    ) {
        val offset = offsetMinutes * 60_000L
        val next = RotationPlan.nextTimeOfDay(now, slots, offset)!!
        val day = 24L * 60 * 60 * 1000
        assertTrue("next $next not after now $now", next > now)
        assertTrue("next more than a day away", next - now <= day)
        val localMinute = (next + offset).mod(day) / 60_000L
        assertTrue("landed on minute $localMinute, slots $slots", localMinute.toInt() in slots)
    }

    @net.jqwik.api.Provide
    fun slots(): net.jqwik.api.Arbitrary<List<Int>> =
        net.jqwik.api.Arbitraries.integers().between(0, 1439).list().ofMinSize(1).ofMaxSize(8)

    @Property(tries = 1000)
    fun anySlotValueIsToleratedWithoutSchedulingIntoThePast(
        @ForAll @LongRange(min = 0, max = 4_102_444_800_000L) now: Long,
        @ForAll @Size(min = 1, max = 6) slots: List<Int>,
    ) {
        val next = RotationPlan.nextTimeOfDay(now, slots, 0)!!
        assertTrue("next $next not after now $now for slots $slots", next > now && next - now <= 24L * 60 * 60 * 1000)
    }

    @Property(tries = 1000)
    fun stepsCoverEachScreenAtMostOnceWithValidFlags(
        @ForAll home: Boolean, @ForAll lock: Boolean, @ForAll same: Boolean, @ForAll homeEmpty: Boolean, @ForAll lockEmpty: Boolean,
    ) {
        val h = if (home) "h" else null
        val l = if (lock) (if (same && home) "h" else "l") else null
        val empty = setOf("h".takeIf { homeEmpty }, "l".takeIf { lockEmpty }).filterNotNull()
        val steps = RotationPlan.steps(h, l) { it in empty }
        val flags = steps.fold(0) { acc, s -> assertEquals("screen written twice", 0, acc and s.which); acc or s.which }
        assertTrue("unknown flag bits in $flags", flags and 3.inv() == 0)
        steps.forEach { assertTrue("empty group scheduled", it.groupId !in empty) }
    }
}

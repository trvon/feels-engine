package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.ui.Format
import dev.fengine.wallpaper.ui.TimeSlots
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TimeSlotsProperties {
    @Property(tries = 500)
    fun validSlotsRoundTripNormalized(
        @ForAll @IntRange(min = 0, max = 1439) first: Int,
        @ForAll @IntRange(min = 0, max = 1439) second: Int,
    ) {
        val draft = listOf(first, second, first).joinToString(", ", transform = Format::clock)
        assertEquals(listOf(first, second).distinct().sorted(), TimeSlots.parse(draft))
    }

    @Property(tries = 500)
    fun anIncompleteSlotNeverDropsTheRestOfTheSchedule(
        @ForAll @IntRange(min = 0, max = 1439) valid: Int,
        @ForAll @IntRange(min = 0, max = 23) incomplete: Int,
    ) {
        assertNull(TimeSlots.parse("${Format.clock(valid)}, $incomplete:"))
    }

    @Property(tries = 500)
    fun arbitraryDraftsNeverThrow(@ForAll draft: String) {
        TimeSlots.parse(draft)?.let { slots ->
            assertEquals(slots.distinct().sorted(), slots)
            check(slots.isNotEmpty() && slots.all { it in 0..1439 })
        }
    }

    /**
     * What the time picker adds is always a clean schedule: in a day, unique, sorted, nothing lost.
     */
    @Property(tries = 1000)
    fun normalizedSlotsAreACleanSchedule(@ForAll("anySlots") slots: List<Int>) {
        val n = TimeSlots.normalize(slots)
        org.junit.Assert.assertEquals(n.sorted().distinct(), n)
        org.junit.Assert.assertTrue(n.all { it in 0 until 24 * 60 })
        org.junit.Assert.assertEquals(slots.map { it.mod(24 * 60) }.toSet(), n.toSet())
    }

    @net.jqwik.api.Provide
    fun anySlots(): net.jqwik.api.Arbitrary<List<Int>> =
        net.jqwik.api.Arbitraries.integers().between(-3000, 3000).list().ofMaxSize(8)
}

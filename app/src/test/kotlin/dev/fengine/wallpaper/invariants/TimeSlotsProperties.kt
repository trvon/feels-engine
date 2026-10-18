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
    fun arbitraryDraftsNeverThrow(
        @ForAll draft: String,
    ) {
        TimeSlots.parse(draft)?.let { slots ->
            assertEquals(slots.distinct().sorted(), slots)
            check(slots.isNotEmpty() && slots.all { it in 0..1439 })
        }
    }
}

package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.ChangeTriggers
import dev.fengine.wallpaper.data.ChangeTriggers.Tap
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ChangeTriggersProperties {
    /** Two taps count only when close in time and place; a lone tap never does. */
    @Property(tries = 1000)
    fun doubleTapNeedsTwoCloseTaps(
        @ForAll @IntRange(min = 0, max = 2000) gap: Int,
        @ForAll @IntRange(min = -300, max = 300) dx: Int,
        @ForAll @IntRange(min = -300, max = 300) dy: Int,
    ) {
        val first = Tap(10_000, 500, 800)
        val second = Tap(10_000L + gap, 500 + dx, 800 + dy)
        val expected =
            gap in 1..ChangeTriggers.DOUBLE_TAP_MS &&
                kotlin.math.abs(dx) <= 120 &&
                kotlin.math.abs(dy) <= 120
        assertEquals(expected, ChangeTriggers.isDoubleTap(first, second, slopPx = 120))
        assertFalse(ChangeTriggers.isDoubleTap(null, second, slopPx = 120))
    }

    /** Only the night bits matter: other uiMode changes (car, desk, a fold) are not a switch. */
    @Property(tries = 500)
    fun onlyTheNightBitsCount(
        @ForAll @IntRange(min = 0, max = 0x3f) a: Int,
        @ForAll @IntRange(min = 0, max = 0x3f) b: Int,
    ) {
        assertEquals((a and 0x30) != (b and 0x30), ChangeTriggers.nightChanged(a, b))
        assertFalse(ChangeTriggers.nightChanged(null, b))
    }

    /** Every stored interval lands on the closest stop; stops round-trip to themselves. */
    @Property(tries = 500)
    fun intervalsLandOnTheNearestStop(@ForAll @IntRange(min = 1, max = 20_000) minutes: Int) {
        val stops = ChangeTriggers.INTERVAL_STOPS
        val i = ChangeTriggers.nearestStop(minutes)
        assertTrue(
            stops.all { kotlin.math.abs(it - minutes) >= kotlin.math.abs(stops[i] - minutes) }
        )
        stops.forEachIndexed { j, m -> assertEquals(j, ChangeTriggers.nearestStop(m)) }
        assertEquals(stops.sorted(), stops)
        assertEquals(15, stops.first())
    }
}

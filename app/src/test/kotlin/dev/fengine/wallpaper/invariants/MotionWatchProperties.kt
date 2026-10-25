package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.MotionWatch
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Motion loss is noticed once, only after Motion was on, and never for a choice of Still. */
class MotionWatchProperties {
    @Property(tries = 1000)
    fun aLossIsReportedOnceAndOnlyAfterMotionWasOn(
        @ForAll("observations") seen: List<Boolean>,
        @ForAll @IntRange(min = 28, max = 37) sdk: Int,
    ) {
        var s = MotionWatch.State(on = false, lostAt = 0)
        var losses = 0
        var expected = 0
        var wasOn = false
        seen.forEachIndexed { i, live ->
            val next = MotionWatch.observe(s, live, sdk, now = 1000L + i)
            if (MotionWatch.justLost(s, next)) losses++
            if (live) {
                assertTrue(next.on)
                assertEquals(0L, next.lostAt)
            }
            if (MotionWatch.canSeeOff(sdk) && wasOn && !live) expected++
            if (MotionWatch.canSeeOff(sdk) || live) wasOn = live
            s = next
        }
        assertEquals(expected, losses)
        if (!MotionWatch.canSeeOff(sdk)) assertEquals(0, losses)
    }

    @Property(tries = 300)
    fun stillIsNeverALoss(
        @ForAll("observations") seen: List<Boolean>,
        @ForAll @IntRange(min = 28, max = 37) sdk: Int,
    ) {
        var s = MotionWatch.still()
        for (live in seen.filter { !it }) {
            val next = MotionWatch.observe(s, live, sdk, now = 5)
            assertFalse(MotionWatch.justLost(s, next))
            s = next
        }
    }

    @Provide
    fun observations(): Arbitrary<List<Boolean>> = Arbitraries.of(true, false).list().ofMaxSize(30)
}

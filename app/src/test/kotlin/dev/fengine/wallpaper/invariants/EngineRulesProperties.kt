package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.wallpaper.live.BitmapSlots
import dev.fengine.wallpaper.wallpaper.live.CanvasPolicy
import dev.fengine.wallpaper.wallpaper.live.FadeClock
import dev.fengine.wallpaper.wallpaper.live.FrameHealth
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class EngineRulesProperties {

    // jqwik ignores range annotations inside a list's type argument; ranges go in generators.
    @Provide
    fun posts(): Arbitrary<List<Long>> = Arbitraries.longs().between(0, 2000).list().ofMaxSize(30)

    @Provide
    fun fadeGaps(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 30).list().ofMinSize(1).ofMaxSize(80)

    /** 0 = decode without fade, 1 = decode with fade, 2 = fade finished. */
    @Provide
    fun slotOps(): Arbitrary<List<Int>> = Arbitraries.integers().between(0, 2).list().ofMaxSize(40)

    @Property(tries = 3000)
    fun bitmapsAreReleasedExactlyOnceAndNeverWhileHeld(@ForAll("slotOps") ops: List<Int>) {
        val slots = BitmapSlots<Int>()
        val released = HashMap<Int, Int>()
        var next = 0
        val added = ArrayList<Int>()
        fun release(list: List<Int>) = list.forEach { released.merge(it, 1, Int::plus) }
        for (op in ops) {
            when (op) {
                0,
                1 -> {
                    val b = next++
                    added += b
                    release(slots.onDecoded(b, fade = op == 1))
                }
                else -> release(slots.onFadeFinished())
            }
            val held = setOfNotNull(slots.current, slots.previous)
            assertTrue(
                "released a bitmap still held: $held vs $released",
                held.none { it in released },
            )
            assertTrue("released twice: $released", released.values.all { it == 1 })
            // No leak: everything ever decoded is either held or released.
            assertEquals(added.toSet(), held + released.keys)
        }
        release(slots.onDestroy())
        assertEquals(added.toSet(), released.keys)
        assertTrue(released.values.all { it == 1 })
    }

    @Property(tries = 2000)
    fun softwareCanvasAfterTwoConsecutiveStallsAndForever(@ForAll("posts") posts: List<Long>) {
        val p = CanvasPolicy(slowMs = 500)
        var consecutive = 0
        var expectSoftware = false
        for (ms in posts) {
            val switched = p.onPost(ms)
            if (!expectSoftware) {
                consecutive = if (ms > 500) consecutive + 1 else 0
                if (consecutive >= 2) {
                    expectSoftware = true
                    assertTrue(switched)
                } else assertFalse(switched)
            } else assertFalse("switched twice", switched)
            assertEquals(expectSoftware, p.softwareOnly)
        }
    }

    @Property(tries = 2000)
    fun fadePacingKeepsTheLastFrameAndSpacesTheRest(@ForAll("fadeGaps") gaps: List<Int>) {
        val clock = FadeClock(minFrameMs = 15)
        var now = 0L
        val drawn = ArrayList<Long>()
        gaps.forEachIndexed { i, g ->
            now += g
            val t = if (i == gaps.lastIndex) 1f else (i + 1f) / (gaps.size + 1f)
            if (clock.shouldDraw(t, now)) drawn += now
            if (i == gaps.lastIndex) assertEquals("final fade frame skipped", now, drawn.last())
        }
        val spaced = drawn.dropLast(1)
        spaced.zipWithNext().forEach { (a, b) ->
            assertTrue("frames ${b - a} ms apart", b - a >= 15)
        }
    }

    /** A blank screen counts only once it was shown for the threshold; shorter is a transition. */
    @Property(tries = 1000)
    fun shortBlanksAreTransitions(@ForAll @LongRange(min = 0, max = 5000) shownFor: Long) {
        val h = FrameHealth(minBlankMs = 1000)
        h.shown(10_000)
        val rs = h.hidden(10_000 + shownFor)
        assertEquals(shownFor >= 1000, rs.any { it is FrameHealth.Record.NoFrame })
    }

    /** 0 shown, 1 drew clean, 2 drew blended, 3 hidden. */
    @Provide
    fun healthOps(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 3).list().ofMaxSize(50)

    @Property(tries = 3000)
    fun everyShownScreenEndsInOneFrameRecordAndBlendsAreReported(
        @ForAll("healthOps") ops: List<Int>
    ) {
        val h = FrameHealth(minBlankMs = 0)
        var now = 0L
        var open = false
        var lastBlended = false
        var shows = 0
        var closes = 0
        for (op in ops) {
            now += 7
            when (op) {
                0 -> {
                    if (!open) {
                        open = true
                        shows++
                    }
                    h.shown(now)
                }
                1,
                2 -> {
                    val r = h.drew(now, blended = op == 2)
                    if (open) {
                        assertTrue(r is FrameHealth.Record.FirstFrame)
                        closes++
                        open = false
                    } else assertEquals(null, r)
                    lastBlended = op == 2
                }
                else -> {
                    val rs = h.hidden(now)
                    assertEquals(open, rs.any { it is FrameHealth.Record.NoFrame })
                    assertEquals(lastBlended, rs.contains(FrameHealth.Record.AbandonedBlend))
                    if (open) closes++
                    open = false
                    lastBlended = false
                }
            }
        }
        val tail = h.hidden(now + 1)
        if (open) closes++
        assertEquals("every shown screen closed exactly once", shows, closes)
        assertEquals(open, tail.any { it is FrameHealth.Record.NoFrame })
    }
}

package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.Ledger.Event
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LedgerProperties {
    @Provide
    fun entries(): Arbitrary<List<Ledger.Entry>> {
        val ev: Arbitrary<Event> = Arbitraries.oneOf(
            Combinators.combine(Arbitraries.of("schedule", "next", "tile", "widget", "still", "show-now"), Arbitraries.of(1, 2, 3), Arbitraries.of(0, 4, 16), Arbitraries.of("applied", "live", "failed"))
                .`as` { s: String, w: Int, p: Int, r: String -> Event.Rotation(s, w, p, r) },
            Arbitraries.longs().between(0, 5000).map { Event.FirstFrame(it) },
            Arbitraries.longs().between(0, 60_000).map { Event.NoFrame(it) },
            Arbitraries.just(Event.AbandonedBlend),
            Combinators.combine(Arbitraries.of("PLAY", "SKIP_CAR", "SKIP_WIRED", "SKIP_EMPTY"), Arbitraries.of(0, 1, 2, 4, 8)).`as` { v: String, p: Int -> Event.ScreenSaver(v, p) },
        )
        return Combinators.combine(Arbitraries.longs().between(0, 10_000_000), ev).`as` { t: Long, e: Event -> Ledger.Entry(t, e) }.list().ofMaxSize(60)
    }

    @Property(tries = 1000)
    fun everyEventRoundTrips(@ForAll("entries") es: List<Ledger.Entry>) {
        for (e in es) assertEquals(e, Ledger.decode(Ledger.encode(e)))
    }

    @Property(tries = 300)
    fun damagedOrUnknownLinesAreSkipped(@ForAll s: String) {
        if (s.contains("\"type\"")) return
        assertNull(Ledger.decode(s))
        assertNull(Ledger.decode("""{"t":1,"type":"from_the_future"}"""))
    }

    @Property(tries = 1000)
    fun summaryCountsEveryEventInTheWindowOnce(@ForAll("entries") es: List<Ledger.Entry>, @ForAll @IntRange(min = 0, max = 10_000_000) from: Int, @ForAll @IntRange(min = 0, max = 10_000_000) span: Int) {
        val to = from.toLong() + span
        val s = Ledger.summarize(es, from.toLong(), to)
        val w = es.filter { it.atMs in from..to }.map { it.event }
        val rot = w.filterIsInstance<Event.Rotation>()
        assertEquals(rot.size, s.rotations)
        assertEquals(rot.count { it.result == "failed" }, s.failed)
        assertEquals(rot.count { it.source in Ledger.Source.manual }, s.manual)
        assertEquals(w.count { it is Event.NoFrame }, s.noFrame)
        assertEquals(w.count { it == Event.AbandonedBlend }, s.abandonedBlends)
        assertEquals(w.filterIsInstance<Event.ScreenSaver>().size, s.screenSaver.values.sum())
        val ff = w.filterIsInstance<Event.FirstFrame>().map { it.latencyMs }
        assertEquals(ff.size, s.firstFrames)
        if (ff.isNotEmpty()) {
            assertTrue(s.firstFrameP50!! <= s.firstFrameP95!!)
            assertTrue(s.firstFrameP95!! <= ff.max() && s.firstFrameP50!! >= ff.min())
        }
    }

    @Property(tries = 1000)
    fun percentileIsNearestRank(@ForAll @Size(min = 1, max = 200) xs: List<@net.jqwik.api.constraints.LongRange(min = 0, max = 10_000) Long>, @ForAll @IntRange(min = 1, max = 100) p: Int) {
        val sorted = xs.sorted()
        val v = Ledger.percentile(sorted, p)!!
        // At least p% of values are <= v, and fewer than p% are < v.
        assertTrue(sorted.count { it <= v } * 100 >= p * sorted.size)
        assertTrue(sorted.count { it < v } * 100 < p * sorted.size)
    }
}

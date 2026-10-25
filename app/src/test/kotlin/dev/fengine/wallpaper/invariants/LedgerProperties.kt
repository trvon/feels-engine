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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LedgerProperties {
    @Provide
    fun entries(): Arbitrary<List<Ledger.Entry>> {
        val ev: Arbitrary<Event> =
            Arbitraries.oneOf(
                Combinators.combine(
                        Arbitraries.of("schedule", "next", "tile", "widget", "still", "show-now"),
                        Arbitraries.of(1, 2, 3),
                        Arbitraries.of(0, 4, 16),
                        Arbitraries.of("applied", "live", "failed"),
                    )
                    .`as` { s: String, w: Int, p: Int, r: String ->
                        Event.Rotation(
                            s,
                            w,
                            p,
                            r,
                            bucket = (w * 10) % 60,
                            lateMs = if (p == 4) null else w * 1000L - 2000,
                            reason =
                                if (r == "unreadable")
                                    "SecurityException com.google.android.apps.photos.cloudpicker"
                                else null,
                        )
                    },
                Arbitraries.longs().between(0, 5000).map { Event.FirstFrame(it) },
                Arbitraries.longs().between(0, 60_000).map { Event.NoFrame(it) },
                Arbitraries.just(Event.AbandonedBlend),
                Combinators.combine(
                        Arbitraries.of("PLAY", "SKIP_CAR", "SKIP_WIRED", "SKIP_EMPTY"),
                        Arbitraries.of(0, 1, 2, 4, 8),
                    )
                    .`as` { v: String, p: Int -> Event.ScreenSaver(v, p) },
                Combinators.combine(
                        Arbitraries.of("crash", "anr", "low_memory", "user_stopped"),
                        Arbitraries.of(100, 125, 200, 300, 400),
                    )
                    .`as` { r: String, i: Int -> Event.Exit(r, i) },
                Arbitraries.of(
                        "java.lang.IllegalStateException",
                        "java.lang.OutOfMemoryError at a.b.c(SourceFile:12)",
                    )
                    .map { Event.Crash(it) },
                Arbitraries.just(Event.MotionLost),
            )
        return Combinators.combine(Arbitraries.longs().between(0, 10_000_000), ev)
            .`as` { t: Long, e: Event -> Ledger.Entry(t, e) }
            .list()
            .ofMaxSize(60)
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
    fun summaryCountsEveryEventInTheWindowOnce(
        @ForAll("entries") es: List<Ledger.Entry>,
        @ForAll @IntRange(min = 0, max = 10_000_000) from: Int,
        @ForAll @IntRange(min = 0, max = 10_000_000) span: Int,
    ) {
        val to = from.toLong() + span
        val s = Ledger.summarize(es, from.toLong(), to)
        val w = es.filter { it.atMs in from..to }.map { it.event }
        val rot = w.filterIsInstance<Event.Rotation>()
        assertEquals(rot.size, s.rotations)
        assertEquals(rot.count { it.result == "failed" }, s.failed)
        assertEquals(rot.count { it.result == "unreadable" }, s.removed)
        if (s.removed > 0) assertTrue(Ledger.sentence(s).contains("could no longer be read"))
        assertEquals(rot.count { it.source in Ledger.Source.manual }, s.manual)
        assertEquals(w.count { it is Event.NoFrame }, s.noFrame)
        assertEquals(w.count { it == Event.AbandonedBlend }, s.abandonedBlends)
        assertEquals(w.filterIsInstance<Event.ScreenSaver>().size, s.screenSaver.values.sum())
        assertEquals(w.filterIsInstance<Event.Exit>().size, s.exits.values.sum())
        assertEquals(w.count { it == Event.MotionLost }, s.motionLost)
        if (s.motionLost > 0) assertTrue(Ledger.sentence(s).contains("Motion stopped"))
        if (s.exits.isNotEmpty()) assertTrue(Ledger.sentence(s).contains("App stopped"))
        val ff = w.filterIsInstance<Event.FirstFrame>().map { it.latencyMs }
        assertEquals(ff.size, s.firstFrames)
        if (ff.isNotEmpty()) {
            assertTrue(s.firstFrameP50!! <= s.firstFrameP95!!)
            assertTrue(s.firstFrameP95!! <= ff.max() && s.firstFrameP50!! >= ff.min())
        }
    }

    @Property(tries = 1000)
    fun percentileIsNearestRank(
        @ForAll("samples") xs: List<Long>,
        @ForAll @IntRange(min = 1, max = 100) p: Int,
    ) {
        val sorted = xs.sorted()
        val v = Ledger.percentile(sorted, p)!!
        // At least p% of values are <= v, and fewer than p% are < v.
        assertTrue(sorted.count { it <= v } * 100 >= p * sorted.size)
        assertTrue(sorted.count { it < v } * 100 < p * sorted.size)
    }

    /**
     * Repeated identical screen-saver starts within the quiet window collapse to one line; any
     * change records.
     */
    @Property(tries = 2000)
    fun screenSaverStartsAreDeduplicated(
        @ForAll("gaps") gapsS: List<Int>,
        @ForAll("verdicts") verdicts: List<String>,
        @ForAll("plugs") plugs: List<Int>,
    ) {
        var now = 0L
        var last: Ledger.Entry? = null
        var lines = 0
        var changes = 0
        gapsS.forEachIndexed { i, g ->
            now += g * 1000L
            val v = verdicts.getOrElse(i % maxOf(1, verdicts.size)) { "SKIP_WIRED" }
            val p = plugs.getOrElse(i % maxOf(1, plugs.size)) { 1 }
            val prev = last?.event as? Event.ScreenSaver
            val changed = prev == null || prev.verdict != v || prev.plugged != p
            if (changed) changes++
            val write = Ledger.shouldRecordScreenSaver(last, v, p, now)
            if (changed) assertTrue("a change was not recorded", write)
            if (write) {
                lines++
                last = Ledger.Entry(now, Event.ScreenSaver(v, p))
            }
        }
        // Lines = changes plus at most one per quiet window of elapsed time.
        assertTrue(lines <= changes + (now / (6 * 3600_000L)).toInt() + 1)
    }

    @Property(tries = 100)
    fun theOwnersLoopBecomesOneLine() {
        // 184 restarts 5.3 s apart on a cable (the owner's Fold, 2026-10-23).
        var last: Ledger.Entry? = null
        var lines = 0
        for (i in 0 until 184) {
            val now = 1_000_000L + i * 5_300L
            if (Ledger.shouldRecordScreenSaver(last, "SKIP_WIRED", 1, now)) {
                lines++
                last = Ledger.Entry(now, Event.ScreenSaver("SKIP_WIRED", 1))
            }
        }
        assertEquals(1, lines)
    }

    // jqwik ignores range annotations inside a list's type argument; ranges go in a generator.
    /** Importing Android's exit history again, from the ledger's watermark, adds nothing. */
    @Property(tries = 500)
    fun everyExitIsImportedOnce(@ForAll("exits") exits: List<Triple<Long, Int, Int>>) {
        val first = Ledger.newExits(exits, null)
        assertTrue(
            first.all {
                (it.event as Event.Exit).reason ==
                    Ledger.exitReason(exits.first { e -> e.first == it.atMs }.second)
            }
        )
        assertEquals(exits.count { Ledger.exitReason(it.second) != null }, first.size)
        assertEquals(first.sortedBy { it.atMs }, first)
        val watermark = first.lastOrNull()?.atMs
        assertTrue(Ledger.newExits(exits, watermark).isEmpty())
    }

    @Property(tries = 300)
    fun crashSiteSkipsLibraryFrames(@ForAll("frames") frames: List<String>) {
        val site = Ledger.crashSite("java.lang.IllegalStateException", frames)
        val ours = frames.firstOrNull {
            !it.startsWith("java.") &&
                !it.startsWith("android") &&
                !it.startsWith("kotlin") &&
                !it.startsWith("com.google.")
        }
        assertEquals(
            if (ours == null) "java.lang.IllegalStateException"
            else "java.lang.IllegalStateException at $ours",
            site,
        )
    }

    @Provide
    fun exits(): Arbitrary<List<Triple<Long, Int, Int>>> =
        Combinators.combine(
                Arbitraries.longs().between(0, 1_000_000),
                Arbitraries.integers().between(0, 16),
                Arbitraries.of(100, 200, 400),
            )
            .`as` { t: Long, r: Int, i: Int -> Triple(t, r, i) }
            .list()
            .ofMaxSize(16)
            .uniqueElements { it.first }

    @Provide
    fun frames(): Arbitrary<List<String>> =
        Arbitraries.of(
                "java.lang.Thread.run(Thread.java:1012)",
                "android.os.Handler.dispatchMessage(Handler.java:99)",
                "androidx.work.Worker.doWork(Worker.java:4)",
                "kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)",
                "com.google.mlkit.vision.Labeler.process(Unknown Source:3)",
                "a.b.c(SourceFile:12)",
                "dev.fengine.wallpaper.schedule.Rotation.run(Rotation.kt:60)",
            )
            .list()
            .ofMaxSize(8)

    @Provide
    fun samples(): Arbitrary<List<Long>> =
        Arbitraries.longs().between(0, 10_000).list().ofMinSize(1).ofMaxSize(200)

    @Provide
    fun gaps(): Arbitrary<List<Int>> =
        Arbitraries.integers().between(0, 30_000).list().ofMaxSize(300)

    @Provide
    fun verdicts(): Arbitrary<List<String>> =
        Arbitraries.of("PLAY", "SKIP_WIRED", "SKIP_CAR").list().ofMinSize(1).ofMaxSize(4)

    @Provide
    fun plugs(): Arbitrary<List<Int>> = Arbitraries.of(1, 2, 4).list().ofMinSize(1).ofMaxSize(3)
}

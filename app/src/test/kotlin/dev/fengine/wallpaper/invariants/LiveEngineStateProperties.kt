package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.wallpaper.live.LiveEngineState
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Zero frames at rest, proven: a random stream of engine events never produces a draw while the
 * surface is invisible, never produces a draw for an event that changes nothing, decodes only when
 * the photo or the surface size changed, and starts a fade only over a photo that was on screen.
 */
class LiveEngineStateProperties {

    sealed interface Ev {
        data class Surface(val w: Int, val h: Int) : Ev

        data class Offset(val x: Float) : Ev

        data class Zoom(val z: Float) : Ev

        data class Visible(val v: Boolean) : Ev

        data class Photo(val uri: String?) : Ev

        data class Decoded(val uri: String) : Ev

        data class Fade(val t: Float) : Ev

        /** The surface was destroyed (screen off, panel off). */
        object Lost : Ev {
            override fun toString() = "Lost"
        }

        /** The system asks for the surface's content before showing it. */
        object Style : Ev {
            override fun toString() = "Style"
        }

        object Redraw : Ev {
            override fun toString() = "Redraw"
        }
    }

    @Provide
    fun events(): Arbitrary<List<Ev>> {
        val uris = Arbitraries.of("a", "b", "c").injectNull(0.1)
        val ev: Arbitrary<Ev> =
            Arbitraries.oneOf(
                Combinators.combine(
                        Arbitraries.of(0, 1080, 1848, 2448),
                        Arbitraries.of(0, 1972, 2400, 2448),
                    )
                    .`as` { w: Int, h: Int -> Ev.Surface(w, h) },
                Arbitraries.of(0f, 0.25f, 0.5f, 1f).map { Ev.Offset(it) },
                Arbitraries.of(0f, 0.5f, 1f).map { Ev.Zoom(it) },
                Arbitraries.of(true, false).map { Ev.Visible(it) },
                uris.map { Ev.Photo(it) },
                Arbitraries.of("a", "b", "c").map { Ev.Decoded(it) },
                Arbitraries.of(0f, 0.3f, 0.7f, 1f).map { Ev.Fade(it) },
                Arbitraries.of<Ev>(Ev.Lost, Ev.Redraw, Ev.Style),
            )
        return ev.list().ofMaxSize(60)
    }

    private fun apply(
        s: LiveEngineState,
        e: Ev,
        pendingKeepOld: MutableMap<String, Boolean>,
    ): LiveEngineState.Decision =
        when (e) {
            is Ev.Surface ->
                s.onSurface(e.w, e.h).also { d -> d.decode?.let { pendingKeepOld[it] = d.keepOld } }
            is Ev.Offset -> s.onOffset(e.x)
            is Ev.Zoom -> s.onZoom(e.z)
            is Ev.Visible -> s.onVisibility(e.v)
            is Ev.Photo ->
                s.onPhoto(e.uri).also { d -> d.decode?.let { pendingKeepOld[it] = d.keepOld } }
            is Ev.Decoded -> s.onDecoded(e.uri, pendingKeepOld[e.uri] ?: false)
            is Ev.Fade -> s.onFadeFrame(e.t)
            is Ev.Lost -> s.onSurfaceLost()
            is Ev.Redraw -> s.onRedrawNeeded()
            is Ev.Style -> s.onStyleChanged()
        }

    @Property(tries = 3000)
    fun neverDrawsWhileInvisibleAndNeverForNoOps(@ForAll("events") events: List<Ev>) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        for (e in events) {
            val visibleBefore = s.visible
            val offsetBefore = s.xOffset
            val zoomBefore = s.zoom
            val d = apply(s, e, keep)
            if (d.draw) assertTrue("draw while invisible after $e", s.visible)
            if (e is Ev.Offset && e.x == offsetBefore)
                assertEquals("no-op offset drew", LiveEngineState.Decision.NONE, d)
            if (e is Ev.Zoom && e.z == zoomBefore)
                assertEquals("no-op zoom drew", LiveEngineState.Decision.NONE, d)
            if (e is Ev.Visible && e.v == visibleBefore)
                assertEquals(LiveEngineState.Decision.NONE, d)
            if (d.startFade) assertTrue(s.hasPrevious && s.visible)
            if (!s.visible) assertFalse("fade kept running while invisible", s.fading)
        }
    }

    @Property(tries = 2000)
    fun idleProducesNothing(
        @ForAll("events") prefix: List<Ev>,
        @ForAll @Size(min = 1, max = 30) repeats: List<Int>,
    ) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        prefix.forEach { apply(s, it, keep) }
        // At rest: the same offset, the same zoom, the same visibility, the same photo, again and
        // again.
        var draws = 0
        var decodes = 0
        repeat(repeats.size) {
            for (d in
                listOf(
                    s.onOffset(s.xOffset),
                    s.onZoom(s.zoom),
                    s.onVisibility(s.visible),
                    s.onPhoto(s.wanted),
                    s.onSurface(s.width, s.height),
                )) {
                if (d.draw) draws++
                if (d.decode != null) decodes++
            }
        }
        assertEquals(0, draws)
        assertEquals(0, decodes)
    }

    @Property(tries = 2000)
    fun decodesOnlyWhenPhotoOrSizeChanged(@ForAll("events") events: List<Ev>) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        var decodes = 0
        var changes = 0
        var lastWanted: String? = null
        var lastSize = 0 to 0
        for (e in events) {
            val d = apply(s, e, keep)
            if (d.decode != null) decodes++
            if (s.wanted != lastWanted || (s.width to s.height) != lastSize) {
                changes++
                lastWanted = s.wanted
                lastSize = s.width to s.height
            }
        }
        assertTrue("$decodes decodes for $changes changes", decodes <= changes)
    }

    @Property(tries = 1000)
    fun becomingVisibleDrawsAtMostOnceAndOnlyIfSomethingChanged(
        @ForAll("events") events: List<Ev>
    ) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        events.forEach { apply(s, it, keep) }
        s.onVisibility(false)
        val wasDirty = s.dirty
        val owedDecode = s.pendingDecode
        val d = s.onVisibility(true)
        // An owed decode comes first (its result will draw); otherwise a dirty state redraws once.
        // An owed decode is issued, unless the photo turned out to be decoded already, in which
        // case
        // a dirty state redraws once.
        if (owedDecode) assertTrue(d.decode != null || d.draw == wasDirty)
        else assertEquals(wasDirty, d.draw)
        assertEquals(LiveEngineState.Decision.NONE, s.onVisibility(true))
    }

    /**
     * A redraw request is the system about to show the surface, so it always ends in a frame: now,
     * or after the one decode it issues (which is then the only decode in flight). The visibility
     * callback is not needed for it; One UI skips that callback when a folded phone opens.
     */
    @Property(tries = 3000)
    fun redrawRequestAlwaysEndsInAFrame(@ForAll("events") events: List<Ev>) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        events.forEach { apply(s, it, keep) }
        if (s.wanted == null || s.width == 0 || s.height == 0)
            return // nothing to show: covered by surfaceBeforePhotoStillDecodes
        val inFlight = s.requested
        val d = s.onRedrawNeeded()
        assertTrue("redraw request left the engine invisible", s.visible)
        when {
            d.draw -> Unit
            d.decode != null ->
                assertTrue(
                    "decode result did not draw",
                    s.onDecoded(d.decode!!, d.keepOld).let { it.draw || it.startFade },
                )
            inFlight != null ->
                assertTrue(
                    "in-flight decode result did not draw",
                    s.onDecoded(inFlight, true).let { it.draw || it.startFade },
                )
            else ->
                throw AssertionError(
                    "redraw request produced nothing: $d wanted=${s.wanted} shown=${s.shown}"
                )
        }
    }

    /**
     * The screen never rests on a blended frame. A frame drawn mid-fade shows two photos at once;
     * once the fade is no longer running, either a clean frame has been drawn since, or one is owed
     * (dirty) and the next visibility or redraw request draws it.
     */
    @Property(tries = 3000)
    fun neverRestsOnABlendedFrame(@ForAll("events") events: List<Ev>) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        var blendedOnScreen = false
        for (e in events) {
            val d = apply(s, e, keep)
            if (d.draw) blendedOnScreen = s.fading
            if (e is Ev.Lost) blendedOnScreen = false // the surface and its frame are gone
            if (e is Ev.Surface && (e.w == 0 || e.h == 0))
                blendedOnScreen = false // nothing can be on a 0-size surface
        }
        if (blendedOnScreen && !s.fading) {
            assertTrue("blended frame left on screen and no frame owed", s.dirty)
            val back = if (s.visible) s.onRedrawNeeded() else s.onVisibility(true)
            if (back.draw) return
            // Not drawn now: a decode must be issued or already in flight, and its result draws.
            val pending = back.decode ?: s.requested?.takeIf { it == s.wanted }
            assertTrue(
                "owed clean frame neither drawn nor on its way: $back wanted=${s.wanted} requested=${s.requested}",
                pending != null,
            )
            val result = s.onDecoded(pending!!, keepOld = true)
            assertTrue(
                "decode result did not draw the clean frame",
                result.draw || result.startFade,
            )
        }
    }

    /** A draw the surface refused is owed again: the next visibility or redraw request draws. */
    @Property(tries = 1000)
    fun refusedDrawIsOwedAgain(@ForAll("events") events: List<Ev>) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        events.forEach { apply(s, it, keep) }
        if (s.shown == null) return
        s.onDrawFailed()
        assertTrue(s.dirty)
        assertTrue(s.onRedrawNeeded().draw || s.onRedrawNeeded().decode != null)
    }

    /**
     * A surface that went away comes back with a frame: through the redraw request, or on
     * visibility.
     */
    @Property(tries = 3000)
    fun lostSurfaceGetsItsFrameBack(
        @ForAll("events") events: List<Ev>,
        @ForAll systemAsksFirst: Boolean,
    ) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        events.forEach { apply(s, it, keep) }
        if (s.shown == null)
            return // nothing decoded: the decode (and its draw) follows visibility, covered
        // elsewhere
        s.onSurfaceLost()
        assertFalse(s.visible)
        assertTrue(s.dirty)
        if (systemAsksFirst) {
            assertTrue("redraw request drew nothing", s.onRedrawNeeded().draw)
            // Visibility may still owe a decode for a photo that changed while invisible, but no
            // second draw.
            assertFalse("drew again on visibility", s.onVisibility(true).draw)
        } else {
            assertTrue("visibility drew nothing", s.onVisibility(true).draw)
        }
    }

    @Property(tries = 500)
    fun surfaceBeforePhotoStillDecodes(@ForAll uri: String) {
        val s = LiveEngineState()
        s.onVisibility(true)
        assertNull(s.onPhoto(uri).decode) // no surface yet: remembered, not decoded
        assertEquals(uri, s.onSurface(1080, 2400).decode)
        assertTrue(s.onDecoded(uri, keepOld = true).draw)
    }

    @Property(tries = 3000)
    fun neverDecodesWhileInvisibleAndOwesExactlyOneDecodeWhenVisibleAgain(
        @ForAll("events") events: List<Ev>
    ) {
        val s = LiveEngineState()
        val keep = HashMap<String, Boolean>()
        for (e in events) {
            val d = apply(s, e, keep)
            if (d.decode != null) assertTrue("decode while invisible after $e", s.visible)
        }
        // Whatever happened, going invisible then visible again decodes at most once, and only if
        // the wanted photo is not the one already decoded for this surface.
        s.onVisibility(false)
        // Decide what is owed before the transition: a decode already in flight is not re-issued.
        val owed =
            s.wanted != null &&
                s.width > 0 &&
                s.height > 0 &&
                s.shown != s.wanted &&
                s.requested != s.wanted
        val d = s.onVisibility(true)
        assertEquals(
            "owed=$owed state=${s.wanted}/${s.shown}/${s.requested}",
            owed,
            d.decode != null,
        )
        assertEquals(LiveEngineState.Decision.NONE, s.onVisibility(true))
    }
}

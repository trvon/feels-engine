package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.wallpaper.ClockLegibility
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.DoubleRange
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ClockAndSkipProperties {
    /** Any single colour behind the clock is readable: the better of light or dark text always clears 3:1. */
    @Property(tries = 2000)
    fun aPlainBackgroundIsAlwaysLegible(@ForAll @DoubleRange(min = 0.0, max = 1.0) l: Double, @ForAll @IntRange(min = 1, max = 500) n: Int) {
        val v = ClockLegibility.judge(DoubleArray(n) { l })
        assertTrue("plain luminance $l failed", v.legible)
        assertEquals(0.0, v.failingShare, 0.0)
    }

    /** Half black, half white behind the clock: whichever tone is picked, half the area fails. */
    @Property(tries = 500)
    fun halfBlackHalfWhiteIsBusy(@ForAll @IntRange(min = 2, max = 500) n: Int) {
        val v = ClockLegibility.judge(DoubleArray(n) { if (it % 2 == 0) 0.0 else 1.0 })
        assertFalse(v.legible)
    }

    @Property(tries = 2000)
    fun contrastIsSymmetricAndAtLeastOne(@ForAll @DoubleRange(min = 0.0, max = 1.0) a: Double, @ForAll @DoubleRange(min = 0.0, max = 1.0) b: Double) {
        assertEquals(ClockLegibility.contrast(a, b), ClockLegibility.contrast(b, a), 1e-12)
        assertTrue(ClockLegibility.contrast(a, b) >= 1.0)
    }

    @Property(tries = 2000)
    fun zoneSitsInsideTheCropTopHalf(@ForAll @IntRange(min = 0, max = 4000) l: Int, @ForAll @IntRange(min = 0, max = 4000) t: Int, @ForAll @IntRange(min = 10, max = 4000) w: Int, @ForAll @IntRange(min = 10, max = 4000) h: Int) {
        val z = ClockLegibility.zone(l, t, l + w, t + h)
        assertTrue(z.left >= l && z.right <= l + w && z.left < z.right)
        assertTrue(z.top >= t && z.bottom <= t + h / 2 && z.top < z.bottom)
    }

    @Provide fun uris(): Arbitrary<List<String>> = Arbitraries.of("content://m/1", "content://m/2", "content://m/3", "content://m/4").list().ofMaxSize(5)

    /** Skipped photos never rotate, unless every photo is skipped; then the group still shows them all. */
    @Property(tries = 2000)
    fun skippedPhotosStayOutOfRotation(@ForAll("uris") photos: List<String>, @ForAll("uris") found: List<String>, @ForAll("uris") skipped: List<String>) {
        val g = Group(name = "g", photos = photos, matched = found, skipped = skipped)
        val all = g.allPhotos
        val r = g.rotation
        assertTrue(all.containsAll(r))
        if (all.isNotEmpty()) assertTrue("a screen given to the group went empty", r.isNotEmpty())
        val out = skipped.map(PhotoId::identity).toSet()
        val kept = all.filterNot { PhotoId.identity(it) in out }
        if (kept.isNotEmpty()) { assertEquals(kept, r); assertTrue(r.none { g.isSkipped(it) }) } else assertEquals(all, r)
        // Skipping never removes a photo from the group itself.
        assertEquals(all, g.copy(skipped = emptyList()).allPhotos)
    }

    @Property(tries = 500)
    fun skippedSurvivesTheGroupCodec(@ForAll("uris") photos: List<String>, @ForAll("uris") skipped: List<String>) {
        val g = Group(name = "g", photos = photos, skipped = skipped)
        assertEquals(g, Group.fromJson(g.toJson()))
    }
}

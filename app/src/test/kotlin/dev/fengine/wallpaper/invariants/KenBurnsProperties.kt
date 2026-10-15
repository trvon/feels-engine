package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.screensaver.DreamPlan
import dev.fengine.wallpaper.screensaver.KenBurns
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.FloatRange
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** The screen saver never shows a border, and every photo plays once before any repeats. */
class KenBurnsProperties {

    @Property(tries = 2000)
    fun frameAlwaysCoversTheView(
        @ForAll @IntRange(min = 1, max = 8000) imageW: Int, @ForAll @IntRange(min = 1, max = 8000) imageH: Int,
        @ForAll @IntRange(min = 1, max = 4000) viewW: Int, @ForAll @IntRange(min = 1, max = 4000) viewH: Int,
        @ForAll @FloatRange(min = -0.5f, max = 1.5f) t: Float,
        @ForAll @FloatRange(min = 0.5f, max = 2f) zoomFrom: Float, @ForAll @FloatRange(min = 0.5f, max = 2f) zoomTo: Float,
        @ForAll @FloatRange(min = -1f, max = 2f) fx: Float, @ForAll @FloatRange(min = -1f, max = 2f) fy: Float,
        @ForAll @FloatRange(min = -1f, max = 2f) tx: Float, @ForAll @FloatRange(min = -1f, max = 2f) ty: Float,
    ) {
        val f = KenBurns.frame(imageW, imageH, viewW, viewH, t, zoomFrom, zoomTo, KenBurns.Focal(fx, fy), KenBurns.Focal(tx, ty))
        val w = imageW * f.scale
        val h = imageH * f.scale
        // Float cannot place a 1 px photo scaled to 16 million px to better than its ulp; the
        // tolerance follows the scaled size so the check stays at one hundredth of a pixel elsewhere.
        val eps = 0.01f + 2 * Math.ulp(maxOf(w, h))
        assertTrue("left edge visible", f.dx <= eps)
        assertTrue("top edge visible", f.dy <= eps)
        assertTrue("right edge visible", f.dx + w >= viewW - eps)
        assertTrue("bottom edge visible", f.dy + h >= viewH - eps)
        assertTrue("zoom below cover", f.scale >= KenBurns.cover(imageW, imageH, viewW, viewH) - 1e-5f)
    }

    @Property(tries = 500)
    fun driftIsContinuous(
        @ForAll @IntRange(min = 100, max = 6000) imageW: Int, @ForAll @IntRange(min = 100, max = 6000) imageH: Int,
        @ForAll @IntRange(min = 100, max = 3000) viewW: Int, @ForAll @IntRange(min = 100, max = 3000) viewH: Int,
        @ForAll @FloatRange(min = 0f, max = 0.99f) t: Float,
    ) {
        val from = KenBurns.Focal(0.3f, 0.6f); val to = KenBurns.Focal(0.7f, 0.4f)
        val a = KenBurns.frame(imageW, imageH, viewW, viewH, t, 1f, 1.08f, from, to)
        val b = KenBurns.frame(imageW, imageH, viewW, viewH, t + 0.01f, 1f, 1.08f, from, to)
        // One percent of the drift moves the photo by at most one percent of what the whole drift
        // can move it (focal glide plus zoom growth, in scaled pixels): no jumps, only clamps.
        val w = imageW * b.scale; val h = imageH * b.scale
        assertTrue(kotlin.math.abs(a.dx - b.dx) <= 0.01f * w * (0.4f + 0.08f) + 1f)
        assertTrue(kotlin.math.abs(a.dy - b.dy) <= 0.01f * h * (0.2f + 0.08f) + 1f)
    }

    @Property(tries = 1000)
    fun orderIsAPermutationThatContinuesAfterTheCursor(
        @ForAll @Size(max = 40) photos: List<String>, @ForAll shuffle: Boolean, @ForAll cursor: Int, @ForAll seed: Long,
    ) {
        val out = DreamPlan.order(photos, shuffle, cursor, seed)
        assertEquals(photos.sorted(), out.sorted())
        if (!shuffle && photos.isNotEmpty()) {
            val start = ((cursor + 1) % photos.size + photos.size) % photos.size
            assertEquals(photos[start], out.first())
            assertEquals(photos.drop(start) + photos.take(start), out)
        }
        if (shuffle && photos.isNotEmpty()) assertEquals(out, DreamPlan.order(photos, true, cursor, seed))
    }
}

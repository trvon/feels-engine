package dev.fengine.wallpaper.invariants

import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import dev.fengine.wallpaper.schedule.RotationPlan
import dev.fengine.wallpaper.wallpaper.WallpaperCrop
import dev.fengine.wallpaper.wallpaper.live.LiveFrame
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.FloatRange
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The live engine never shows a border, parallax moves the photo the right way, the fade is
 * monotone, and a decode at the declared target size is enough for every frame.
 */
class LiveFrameProperties {

    @Property(tries = 2000)
    fun frameAlwaysCoversTheSurface(
        @ForAll @IntRange(min = 1, max = 8000) bw: Int, @ForAll @IntRange(min = 1, max = 8000) bh: Int,
        @ForAll @IntRange(min = 1, max = 4000) sw: Int, @ForAll @IntRange(min = 1, max = 4000) sh: Int,
        @ForAll @FloatRange(min = -0.5f, max = 1.5f) xOffset: Float, @ForAll @FloatRange(min = -0.5f, max = 1.5f) zoom: Float,
        @ForAll @FloatRange(min = -0.2f, max = 0.5f) parallax: Float, @ForAll @FloatRange(min = -0.2f, max = 0.5f) zoomRange: Float,
    ) {
        val f = LiveFrame.frame(bw, bh, sw, sh, xOffset, zoom, parallax, zoomRange)
        val eps = 0.01f
        assertTrue(f.dx <= eps); assertTrue(f.dy <= eps)
        assertTrue(f.dx + bw * f.scale >= sw - eps)
        assertTrue(f.dy + bh * f.scale >= sh - eps)
    }

    @Property(tries = 1000)
    fun parallaxPansLeftAsTheOffsetGrows(
        @ForAll @IntRange(min = 100, max = 6000) bw: Int, @ForAll @IntRange(min = 100, max = 6000) bh: Int,
        @ForAll @IntRange(min = 100, max = 3000) sw: Int, @ForAll @IntRange(min = 100, max = 3000) sh: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) a: Float, @ForAll @FloatRange(min = 0f, max = 1f) b: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) zoom: Float,
    ) {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val fa = LiveFrame.frame(bw, bh, sw, sh, lo, zoom); val fb = LiveFrame.frame(bw, bh, sw, sh, hi, zoom)
        assertTrue(fb.dx <= fa.dx + 1e-3f)
        assertEquals(fa.scale, fb.scale, 1e-6f)
        // With no parallax the offset does nothing.
        val n0 = LiveFrame.frame(bw, bh, sw, sh, lo, zoom, parallax = 0f); val n1 = LiveFrame.frame(bw, bh, sw, sh, hi, zoom, parallax = 0f)
        assertEquals(n0.dx, n1.dx, 1e-3f)
    }

    @Property(tries = 500)
    fun zoomOutShrinksTowardCoverAndNeverBelow(
        @ForAll @IntRange(min = 100, max = 6000) bw: Int, @ForAll @IntRange(min = 100, max = 6000) bh: Int,
        @ForAll @IntRange(min = 100, max = 3000) sw: Int, @ForAll @IntRange(min = 100, max = 3000) sh: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) z: Float,
    ) {
        val rest = LiveFrame.frame(bw, bh, sw, sh, 0.5f, 0f); val out = LiveFrame.frame(bw, bh, sw, sh, 0.5f, z)
        assertTrue(out.scale <= rest.scale + 1e-6f)
        assertTrue(out.scale >= LiveFrame.cover(bw, bh, sw, sh) - 1e-6f)
    }

    @Property(tries = 500)
    fun decodeTargetIsEnoughForEveryFrame(
        @ForAll @IntRange(min = 100, max = 3000) sw: Int, @ForAll @IntRange(min = 100, max = 3000) sh: Int,
        @ForAll @IntRange(min = 1000, max = 12000) iw: Int, @ForAll @IntRange(min = 1000, max = 12000) ih: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) xOffset: Float,
    ) {
        // Decoding at sampleSize(target) keeps the bitmap at least as large as the target; the frame
        // at rest (maximum scale) must then never upscale beyond 1x of the decoded pixels.
        val (tw, th) = LiveFrame.decodeTarget(sw, sh)
        Assume.that(iw >= tw && ih >= th) // a photo smaller than the screen has to be upscaled
        val sample = WallpaperCrop.sampleSize(iw, ih, tw, th)
        val bw = iw / sample; val bh = ih / sample
        val f = LiveFrame.frame(bw, bh, sw, sh, xOffset, 0f)
        assertTrue("frame upscales decoded pixels: scale ${f.scale}", f.scale <= 1.0001f)
    }

    @Property(tries = 500)
    fun fadeIsMonotoneAndBounded(@ForAll @FloatRange(min = -1f, max = 2f) a: Float, @ForAll @FloatRange(min = -1f, max = 2f) b: Float) {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val fa = LiveFrame.fadeAlpha(lo); val fb = LiveFrame.fadeAlpha(hi)
        assertTrue(fa in 0f..1f && fb in 0f..1f)
        assertTrue(fa <= fb + 1e-6f)
        assertEquals(0f, LiveFrame.fadeAlpha(0f), 0f); assertEquals(1f, LiveFrame.fadeAlpha(1f), 0f)
    }

    @Property(tries = 200)
    fun staticFlagsDropExactlyTheLiveScreens(@ForAll @IntRange(min = 0, max = 3) which: Int, @ForAll liveHome: Boolean, @ForAll liveLock: Boolean) {
        val out = RotationPlan.staticFlags(which, liveHome, liveLock)
        assertEquals(0, out and which.inv())
        assertEquals(if (liveHome) 0 else which and FLAG_SYSTEM, out and FLAG_SYSTEM)
        assertEquals(if (liveLock) 0 else which and FLAG_LOCK, out and FLAG_LOCK)
    }
}

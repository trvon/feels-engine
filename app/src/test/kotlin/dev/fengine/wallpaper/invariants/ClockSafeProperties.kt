package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.wallpaper.ClockSafe
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
 * Faces stay out from under the lock screen clock whenever the photo has room, and the clock-safe
 * placement keeps every promise the plain crop and frame make (inside the image, same size, the
 * surface covered).
 */
class ClockSafeProperties {

    @Property(tries = 3000)
    fun startStaysInRangeAndClearsTheBandWhenItCan(
        @ForAll @FloatRange(min = 1f, max = 5000f) length: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) viewFrac: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) a0: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) b0: Float,
        @ForAll @FloatRange(min = -1f, max = 2f) cur: Float,
        @ForAll @FloatRange(min = 0f, max = 0.6f) band: Float,
    ) {
        val view = (length * viewFrac).coerceAtLeast(1f).coerceAtMost(length)
        val a = minOf(a0, b0) * length
        val b = maxOf(a0, b0) * length
        val t = ClockSafe.start(length, view, a, b, cur * length, band)
        val eps = 1e-2f
        assertTrue(t >= -eps && t <= length - view + eps)
        val feasible = b > a && maxOf(b - view, 0f) <= minOf(a - band * view, length - view)
        if (feasible) {
            assertTrue("faces below the band", a >= t + band * view - eps)
            assertTrue("faces inside the window", b <= t + view + eps)
        } else {
            assertEquals(cur.times(length).coerceIn(0f, length - view), t, eps)
        }
    }

    @Property(tries = 2000)
    fun lockCropIsAPlainCropMovedOnlyVertically(
        @ForAll @IntRange(min = 10, max = 8000) iw: Int,
        @ForAll @IntRange(min = 10, max = 8000) ih: Int,
        @ForAll @IntRange(min = 10, max = 4000) tw: Int,
        @ForAll @IntRange(min = 10, max = 4000) th: Int,
        @ForAll @FloatRange(min = 0f, max = 0.9f) x: Float,
        @ForAll @FloatRange(min = 0f, max = 0.9f) y: Float,
        @ForAll @FloatRange(min = 0.01f, max = 0.3f) size: Float,
    ) {
        val face = ClockSafe.Box(x, y, (x + size).coerceAtMost(1f), (y + size).coerceAtMost(1f))
        val c = ClockSafe.crop(iw, ih, tw, th, listOf(face))
        val plain = WallpaperCrop.fit(iw, ih, tw, th)
        assertEquals(plain.width, c.width)
        assertEquals(plain.height, c.height)
        assertTrue(c.left >= 0 && c.top >= 0 && c.right <= iw && c.bottom <= ih)
        // No faces: exactly the plain crop.
        assertEquals(plain, ClockSafe.crop(iw, ih, tw, th, emptyList()))
    }

    @Property(tries = 2000)
    fun lockCropClearsTheBandWhenThePhotoIsTallEnough(
        @ForAll @IntRange(min = 2600, max = 9000) ih: Int,
        @ForAll @FloatRange(min = 0f, max = 0.95f) y: Float,
        @ForAll @FloatRange(min = 0.01f, max = 0.1f) size: Float,
    ) {
        // A portrait photo taller than the portrait screen's aspect leaves vertical room.
        val iw = 1000
        val face = ClockSafe.Box(0.45f, y, 0.45f + size, (y + size).coerceAtMost(1f))
        val c = ClockSafe.crop(iw, ih, 1080, 2400, listOf(face))
        val top = face.top * ih
        val bottom = face.bottom * ih
        val h = c.height
        Assume.that(
            maxOf(bottom - h, 0f) <= minOf(top - ClockSafe.BAND * h, (ih - h).toFloat()) - 1
        )
        assertTrue("face below the clock band", top >= c.top + ClockSafe.BAND * h - 1)
        assertTrue("face on screen", bottom <= c.bottom + 1)
    }

    @Property(tries = 2000)
    fun liveFrameWithFacesStillCoversTheSurface(
        @ForAll @IntRange(min = 1, max = 8000) bw: Int,
        @ForAll @IntRange(min = 1, max = 8000) bh: Int,
        @ForAll @IntRange(min = 1, max = 4000) sw: Int,
        @ForAll @IntRange(min = 1, max = 4000) sh: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) zoom: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) a: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) b: Float,
    ) {
        val face = ClockSafe.Box(0.4f, minOf(a, b), 0.6f, maxOf(a, b))
        val f = LiveFrame.frame(bw, bh, sw, sh, 0.5f, zoom, faces = face)
        val eps = 0.01f
        assertTrue(f.dx <= eps)
        assertTrue(f.dy <= eps)
        assertTrue(f.dx + bw * f.scale >= sw - eps)
        assertTrue(f.dy + bh * f.scale >= sh - eps)
        // Faces never change the scale or the horizontal placement.
        val plain = LiveFrame.frame(bw, bh, sw, sh, 0.5f, zoom)
        assertEquals(plain.scale, f.scale, 1e-6f)
        assertEquals(plain.dx, f.dx, 1e-3f)
    }
}

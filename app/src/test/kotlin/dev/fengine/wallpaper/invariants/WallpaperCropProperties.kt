package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.wallpaper.WallpaperCrop
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.FloatRange
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** Invariants of the crop maths for any photo size, any display size, any focal point. */
class WallpaperCropProperties {

    @Property(tries = 2000)
    fun cropStaysInsideTheImageAndIsNonEmpty(
        @ForAll @IntRange(min = 1, max = 12000) iw: Int, @ForAll @IntRange(min = 1, max = 12000) ih: Int,
        @ForAll @IntRange(min = 1, max = 4000) tw: Int, @ForAll @IntRange(min = 1, max = 4000) th: Int,
        @ForAll @FloatRange(min = -1f, max = 2f) fx: Float, @ForAll @FloatRange(min = -1f, max = 2f) fy: Float,
    ) {
        val c = WallpaperCrop.fit(iw, ih, tw, th, fx, fy)
        assertTrue("inside image: $c for $iw x $ih", c.left >= 0 && c.top >= 0 && c.right <= iw && c.bottom <= ih)
        assertTrue("non-empty: $c", c.width >= 1 && c.height >= 1)
    }

    @Property(tries = 2000)
    fun cropMatchesTargetAspectWithinRounding(
        @ForAll @IntRange(min = 16, max = 12000) iw: Int, @ForAll @IntRange(min = 16, max = 12000) ih: Int,
        @ForAll @IntRange(min = 16, max = 4000) tw: Int, @ForAll @IntRange(min = 16, max = 4000) th: Int,
    ) {
        val c = WallpaperCrop.fit(iw, ih, tw, th)
        val want = tw.toDouble() / th
        val got = c.width.toDouble() / c.height
        // The crop's shorter edge is rounded down to a whole pixel (never below one), so the relative
        // aspect error is bounded by one pixel over the ideal shorter edge.
        val ideal = if (iw.toDouble() / ih > want) minOf(ih * want, ih.toDouble()) else minOf(iw.toDouble(), iw / want)
        // Below one ideal pixel the crop is clamped to a 1 px edge and no aspect can be honoured.
        Assume.that(ideal >= 1.0)
        val relative = abs(got - want) / want
        assertTrue("aspect $got vs $want (crop $c, image $iw x $ih, target $tw x $th)", relative <= 1.0 / kotlin.math.floor(ideal).coerceAtLeast(1.0) + 1e-9)
    }

    @Property(tries = 2000)
    fun cropIsMaximal(
        @ForAll @IntRange(min = 16, max = 12000) iw: Int, @ForAll @IntRange(min = 16, max = 12000) ih: Int,
        @ForAll @IntRange(min = 16, max = 4000) tw: Int, @ForAll @IntRange(min = 16, max = 4000) th: Int,
    ) {
        val c = WallpaperCrop.fit(iw, ih, tw, th)
        assertTrue("one full edge must be used: $c for $iw x $ih -> $tw x $th", c.width == iw || c.height == ih)
    }

    @Property(tries = 2000)
    fun cropContainsTheFocalPoint(
        @ForAll @IntRange(min = 16, max = 12000) iw: Int, @ForAll @IntRange(min = 16, max = 12000) ih: Int,
        @ForAll @IntRange(min = 16, max = 4000) tw: Int, @ForAll @IntRange(min = 16, max = 4000) th: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) fx: Float, @ForAll @FloatRange(min = 0f, max = 1f) fy: Float,
    ) {
        val c = WallpaperCrop.fit(iw, ih, tw, th, fx, fy)
        val px = (fx * iw).toInt().coerceAtMost(iw - 1)
        val py = (fy * ih).toInt().coerceAtMost(ih - 1)
        assertTrue("focal ($px,$py) outside $c", px in c.left until c.right + 1 && py in c.top until c.bottom + 1)
    }

    @Property(tries = 1000)
    fun sampleSizeNeverDropsBelowTheDisplay(
        @ForAll @IntRange(min = 1, max = 20000) iw: Int, @ForAll @IntRange(min = 1, max = 20000) ih: Int,
        @ForAll @IntRange(min = 1, max = 4000) tw: Int, @ForAll @IntRange(min = 1, max = 4000) th: Int,
    ) {
        val s = WallpaperCrop.sampleSize(iw, ih, tw, th)
        assertEquals("power of two: $s", 0, s and (s - 1))
        if (iw >= tw && ih >= th) assertTrue("sample $s too aggressive for $iw x $ih -> $tw x $th", iw / s >= tw && ih / s >= th)
        else assertEquals(1, s)
    }
}

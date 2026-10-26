package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Appearance
import dev.fengine.wallpaper.data.ThemeMode
import dev.fengine.wallpaper.screensaver.KenBurns
import dev.fengine.wallpaper.ui.Palette
import dev.fengine.wallpaper.wallpaper.live.LiveFrame
import kotlin.math.abs
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.FloatRange
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Theme choices, photo-derived colours and the whole-photo fit. */
class PaletteProperties {
    @Provide fun colors(): Arbitrary<Int> = Arbitraries.integers().map { it or (0xFF shl 24) }

    @Property(tries = 300)
    fun themeChoiceWinsOverTheSystem(@ForAll systemDark: Boolean) {
        assertEquals(systemDark, Appearance.isDark(ThemeMode.SYSTEM, systemDark))
        assertEquals(false, Appearance.isDark(ThemeMode.LIGHT, systemDark))
        assertEquals(true, Appearance.isDark(ThemeMode.DARK, systemDark))
    }

    /**
     * Every text colour on every derived background meets WCAG 4.5:1, for any photo colour, light
     * or dark.
     */
    @Property(tries = 5000)
    fun everyDerivedSchemeIsReadable(@ForAll("colors") seed: Int, @ForAll dark: Boolean) {
        val s = Palette.scheme(seed, dark)
        for ((bg, fg) in
            listOf(
                s.primary to s.onPrimary,
                s.primaryContainer to s.onPrimaryContainer,
                s.secondary to s.onSecondary,
                s.secondaryContainer to s.onSecondaryContainer,
                s.background to s.onBackground,
                s.surfaceVariant to s.onSurfaceVariant,
                s.surfaceContainer to s.onBackground,
                // Primary and secondary are text too: buttons and eyebrows on the page and cards.
                s.background to s.primary,
                s.surfaceContainer to s.primary,
                s.background to s.secondary,
                s.surfaceContainer to s.secondary,
            )) {
            assertTrue(
                "contrast ${Palette.contrast(bg, fg)} for seed ${Integer.toHexString(seed)} dark=$dark",
                Palette.contrast(bg, fg) >= 4.5,
            )
        }
        // Dark schemes have dark backgrounds, light schemes light ones.
        if (dark) assertTrue(Palette.luminance(s.background) < 0.05)
        else assertTrue(Palette.luminance(s.background) > 0.8)
    }

    @Property(tries = 3000)
    fun backdropIsDarkAndKeepsTheHue(@ForAll("colors") seed: Int) {
        val b = Palette.backdrop(seed)
        assertTrue(
            "backdrop too light: ${Palette.hsl(b).l}",
            Palette.hsl(b).l <= maxOf(Palette.MAX_BACKDROP_LIGHTNESS, 0f) + 0.01f,
        )
        assertTrue(
            "backdrop brighter than the photo colour",
            Palette.luminance(b) <= Palette.luminance(seed) + 1e-9,
        )
        val hs = Palette.hsl(seed)
        val hb = Palette.hsl(b)
        if (hs.s > 0.2f && hs.l in 0.1f..0.9f && hb.s > 0.2f) {
            val d = abs(hs.h - hb.h).let { minOf(it, 360f - it) }
            assertTrue("hue drifted $d", d < 12f)
        }
    }

    @Property(tries = 3000)
    fun hslRoundTripsWithinRounding(@ForAll("colors") c: Int) {
        val back = Palette.argb(Palette.hsl(c))
        for (shift in listOf(16, 8, 0)) assertTrue(
            abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 2
        )
    }

    /**
     * The whole-photo fit shows all of the photo, centred, touching two opposite edges, for the
     * engine and the screen saver.
     */
    @Property(tries = 3000)
    fun wholePhotoShowsEverything(
        @ForAll @IntRange(min = 1, max = 8000) iw: Int,
        @ForAll @IntRange(min = 1, max = 8000) ih: Int,
        @ForAll @IntRange(min = 1, max = 4000) vw: Int,
        @ForAll @IntRange(min = 1, max = 4000) vh: Int,
        @ForAll @FloatRange(min = 0f, max = 1f) x: Float,
        @ForAll @FloatRange(min = 0f, max = 1f) z: Float,
    ) {
        val frames =
            listOf(
                LiveFrame.frame(iw, ih, vw, vh, x, z, whole = true).let {
                    Triple(it.scale, it.dx, it.dy)
                },
                KenBurns.frame(
                        iw,
                        ih,
                        vw,
                        vh,
                        x,
                        1f,
                        1.1f,
                        KenBurns.Focal(0.2f, 0.3f),
                        KenBurns.Focal(0.7f, 0.6f),
                        whole = true,
                    )
                    .let { Triple(it.scale, it.dx, it.dy) },
            )
        for ((s, dx, dy) in frames) {
            val w = iw * s
            val h = ih * s
            val eps = 0.5f + 2 * Math.ulp(maxOf(w, h, vw.toFloat(), vh.toFloat()))
            assertTrue(dx >= -eps && dy >= -eps && dx + w <= vw + eps && dy + h <= vh + eps)
            assertTrue("not touching two edges", abs(w - vw) <= eps || abs(h - vh) <= eps)
            assertTrue(
                "not centred",
                abs(dx - (vw - w - dx)) <= 2 * eps && abs(dy - (vh - h - dy)) <= 2 * eps,
            )
        }
    }
}

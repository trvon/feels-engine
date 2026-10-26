package dev.fengine.wallpaper.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colours from a photo's main colour, as plain ARGB ints so the rules are property-tested without
 * Compose (`invariants/PaletteProperties`). Used for "Colours: From photo" on any Android version
 * and for the "Photo colours" backdrop behind a whole photo.
 */
object Palette {
    data class Hsl(val h: Float, val s: Float, val l: Float)

    data class Scheme(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val background: Int,
        val onBackground: Int,
        val surfaceVariant: Int,
        val onSurfaceVariant: Int,
        /**
         * Tonal containers (settings cards) in the photo's hue; text on them uses [onBackground].
         */
        val surfaceContainer: Int,
        /** Borders (segmented buttons, switch tracks) in the photo's hue. */
        val outline: Int = surfaceVariant,
        val outlineVariant: Int = surfaceVariant,
    )

    fun hsl(argb: Int): Hsl {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return Hsl(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h =
            when (mx) {
                r -> ((g - b) / d + (if (g < b) 6 else 0))
                g -> ((b - r) / d + 2)
                else -> ((r - g) / d + 4)
            } * 60f
        return Hsl(h, s, l)
    }

    fun argb(c: Hsl): Int {
        val s = c.s.coerceIn(0f, 1f)
        val l = c.l.coerceIn(0f, 1f)
        val h = ((c.h % 360f) + 360f) % 360f
        val q = (1 - abs(2 * l - 1)) * s
        val x = q * (1 - abs((h / 60f) % 2 - 1))
        val m = l - q / 2
        val (r, g, b) =
            when {
                h < 60 -> Triple(q, x, 0f)
                h < 120 -> Triple(x, q, 0f)
                h < 180 -> Triple(0f, q, x)
                h < 240 -> Triple(0f, x, q)
                h < 300 -> Triple(x, 0f, q)
                else -> Triple(q, 0f, x)
            }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** WCAG relative luminance. */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) +
            0.7152 * ch((argb shr 8) and 0xFF) +
            0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /**
     * Text colour for a background: whichever of white or black reads better (always at least
     * 4.5:1).
     */
    fun onColor(background: Int): Int =
        if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK

    /** The photo's colour at a given lightness, keeping its hue and some of its saturation. */
    private fun tone(seed: Hsl, l: Float, s: Float = seed.s) =
        argb(Hsl(seed.h, s.coerceIn(0f, 0.85f), l))

    /**
     * A full scheme from one seed colour, light or dark. Every on-colour meets WCAG 4.5:1 on its
     * background.
     */
    fun scheme(seed: Int, dark: Boolean): Scheme {
        val c = hsl(seed)
        val sat = max(c.s, 0.25f) // greys still get a faint tint so the scheme is not flat
        fun pair(l: Float, s: Float = sat): Pair<Int, Int> {
            val bg = tone(c, l, s)
            return bg to onColor(bg)
        }
        val (bg, onBg) = pair(if (dark) 0.07f else 0.98f, sat * 0.25f)
        val container = tone(c, if (dark) 0.12f else 0.95f, sat * 0.2f)
        // Primary and secondary are also text (buttons, eyebrows) on the page and on cards: move
        // their lightness away from the background until both read at 4.5:1. A yellow or green
        // photo needs a darker primary in light mode than its hue suggests.
        fun readable(l: Float, s: Float): Pair<Int, Int> {
            var light = l
            var bgc = tone(c, light, s)
            while (
                min(contrast(bgc, bg), contrast(bgc, container)) < 4.5 && light in 0.02f..0.98f
            ) {
                light += if (dark) 0.02f else -0.02f
                bgc = tone(c, light, s)
            }
            return bgc to onColor(bgc)
        }
        val (p, onP) = readable(if (dark) 0.78f else 0.38f, sat)
        val (pc, onPc) = pair(if (dark) 0.28f else 0.88f)
        val (s2, onS) = readable(if (dark) 0.75f else 0.42f, sat * 0.45f)
        val (sc, onSc) = pair(if (dark) 0.26f else 0.90f, sat * 0.45f)
        val (sv, onSv) = pair(if (dark) 0.18f else 0.91f, sat * 0.2f)
        return Scheme(
            p,
            onP,
            pc,
            onPc,
            s2,
            onS,
            sc,
            onSc,
            bg,
            onBg,
            sv,
            onSv,
            container,
            outline = tone(c, if (dark) 0.60f else 0.48f, sat * 0.25f),
            outlineVariant = tone(c, if (dark) 0.30f else 0.82f, sat * 0.2f),
        )
    }

    /** The backdrop around a whole photo: its colour, dark enough that the photo's edge reads. */
    fun backdrop(seed: Int): Int {
        val c = hsl(seed)
        val l = min(c.l, MAX_BACKDROP_LIGHTNESS)
        val lum = luminance(seed)
        // A duller shade first. Desaturating a deep blue adds red and green, which read brighter,
        // so fall back to the same saturation made darker, and near black to the colour itself.
        val duller = argb(Hsl(c.h, min(c.s, 0.6f), l))
        if (luminance(duller) <= lum) return duller
        val darker = argb(Hsl(c.h, c.s, l))
        if (luminance(darker) <= lum) return darker
        return seed or (0xFF shl 24)
    }

    const val MAX_BACKDROP_LIGHTNESS = 0.16f
}

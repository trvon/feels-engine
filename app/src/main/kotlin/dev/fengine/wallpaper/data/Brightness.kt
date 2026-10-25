package dev.fengine.wallpaper.data

import kotlin.math.cbrt
import kotlin.math.pow

/** Whether a photo reads as dark, from a tiny decode of it. Pure. */
object Brightness {
    /** Mean CIE lightness (0 black ... 100 white) at or under which a photo counts as dark. */
    const val DARK_MAX_LSTAR = 40.0

    /** CIE L* of an opaque ARGB pixel. */
    fun lightness(argb: Int): Double {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val y =
            0.2126 * lin((argb shr 16) and 0xFF) +
                0.7152 * lin((argb shr 8) and 0xFF) +
                0.0722 * lin(argb and 0xFF)
        return if (y > 216.0 / 24389) 116 * cbrt(y) - 16 else y * 24389 / 27
    }

    /**
     * Mean lightness of [pixels]; 100 (bright) for none, so an empty decode never counts as dark.
     */
    fun meanLightness(pixels: IntArray): Double =
        if (pixels.isEmpty()) 100.0 else pixels.sumOf { lightness(it) } / pixels.size

    fun isDark(pixels: IntArray): Boolean = meanLightness(pixels) <= DARK_MAX_LSTAR
}

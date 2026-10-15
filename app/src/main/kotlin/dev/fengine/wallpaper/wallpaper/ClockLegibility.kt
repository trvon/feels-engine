package dev.fengine.wallpaper.wallpaper

import kotlin.math.pow

/**
 * Will the lock-screen clock read well over this photo? The system picks light or dark clock
 * text to suit the wallpaper, so one tone is chosen for the area's average brightness; the photo
 * fails when too much of the area behind the clock falls under WCAG's 3:1 contrast for large text
 * (SC 1.4.3, background images: failure F83) against that tone. Pure; property-tested.
 */
object ClockLegibility {
    const val MIN_CONTRAST = 3.0
    /** Share of the clock area allowed under [MIN_CONTRAST] before the photo is called busy. */
    const val MAX_FAILING_SHARE = 0.10

    data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Where lock-screen clocks sit on a portrait crop: 10 to 40 percent of the height, the middle
     * 80 percent of the width. Pixel's large clock and One UI's clock both fall inside; the exact
     * place varies by phone and clock style, so this is a conservative band, not a measurement.
     */
    fun zone(left: Int, top: Int, right: Int, bottom: Int): Zone {
        val w = right - left; val h = bottom - top
        return Zone(left + w / 10, top + h / 10, right - w / 10, top + h * 4 / 10)
    }

    /** WCAG relative luminance of an sRGB colour (alpha ignored). */
    fun relativeLuminance(argb: Int): Double {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    data class Verdict(val lightText: Boolean, val failingShare: Double) {
        val legible: Boolean get() = failingShare <= MAX_FAILING_SHARE
    }

    fun judge(luminances: DoubleArray): Verdict {
        if (luminances.isEmpty()) return Verdict(lightText = true, failingShare = 0.0)
        val mean = luminances.average()
        val light = contrast(1.0, mean) >= contrast(0.0, mean)
        val text = if (light) 1.0 else 0.0
        val failing = luminances.count { contrast(text, it) < MIN_CONTRAST }
        return Verdict(light, failing.toDouble() / luminances.size)
    }
}

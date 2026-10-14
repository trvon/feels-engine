package dev.fengine.wallpaper.screensaver

import kotlin.math.max

/**
 * The slow drift a screen saver gives a still photo: cover the view, then zoom a little and
 * glide between two focal points. Pure maths over sizes in pixels so the invariant suite can
 * prove the view never shows an edge, whatever the sizes and wherever the focal points land.
 */
object KenBurns {
    /** Where the photo sits: scaled by [scale] about its origin, then moved by ([dx], [dy]). */
    data class Frame(val scale: Float, val dx: Float, val dy: Float)

    /** A point in the photo as fractions of its width and height. */
    data class Focal(val x: Float, val y: Float)

    /** Smallest scale at which the photo covers the view. */
    fun cover(imageW: Int, imageH: Int, viewW: Int, viewH: Int): Float =
        max(viewW.toFloat() / imageW, viewH.toFloat() / imageH)

    /**
     * The frame at progress [t] in 0..1 while zooming from [zoomFrom] to [zoomTo] (both >= 1,
     * relative to [cover]) and gliding from [from] to [to]. The focal point is kept at the view's
     * centre as far as the photo's edges allow; it is clamped before it could reveal a border.
     */
    fun frame(
        imageW: Int, imageH: Int, viewW: Int, viewH: Int,
        t: Float, zoomFrom: Float, zoomTo: Float, from: Focal, to: Focal,
    ): Frame {
        val p = t.coerceIn(0f, 1f)
        val scale = cover(imageW, imageH, viewW, viewH) * lerp(zoomFrom, zoomTo, p).coerceAtLeast(1f)
        // Float rounding can leave the scaled photo a hair smaller than the view; never let the
        // clamp range invert, and never let the photo be smaller than the view it must cover.
        val w = maxOf(imageW * scale, viewW.toFloat())
        val h = maxOf(imageH * scale, viewH.toFloat())
        val fx = lerp(from.x, to.x, p).coerceIn(0f, 1f)
        val fy = lerp(from.y, to.y, p).coerceIn(0f, 1f)
        val dx = (viewW / 2f - fx * w).coerceIn(viewW - w, 0f)
        val dy = (viewH / 2f - fy * h).coerceIn(viewH - h, 0f)
        return Frame(scale, dx, dy)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

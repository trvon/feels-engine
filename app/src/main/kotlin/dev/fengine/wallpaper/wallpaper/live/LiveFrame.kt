package dev.fengine.wallpaper.wallpaper.live

import kotlin.math.ceil
import kotlin.math.max

/**
 * Where the live engine draws a photo: pure maths over sizes, the launcher's page offset and the
 * system's zoom, so the invariant suite can prove the surface is always covered.
 *
 * The photo is scaled to cover the surface with extra width for parallax ([PARALLAX]) and extra
 * size for the zoom-out effect ([ZOOM]). At rest (zoom 0) it is drawn slightly enlarged; when the
 * system asks for zoom-out (zoom 1, app drawer or lock transition) it relaxes toward the plain
 * cover scale. Page offset pans across the parallax overscan.
 */
object LiveFrame {
    /** Horizontal overscan as a fraction of the surface width. */
    const val PARALLAX = 0.08f
    /** Extra scale at rest that the zoom-out effect releases. */
    const val ZOOM = 0.06f

    data class Frame(val scale: Float, val dx: Float, val dy: Float)

    /** Smallest scale at which the bitmap covers the surface. */
    fun cover(bitmapW: Int, bitmapH: Int, surfaceW: Int, surfaceH: Int): Float =
        max(surfaceW.toFloat() / bitmapW, surfaceH.toFloat() / bitmapH)

    /**
     * [xOffset] is the launcher page offset in 0..1 (0.5 when a launcher reports none), [zoom] the
     * system zoom in 0..1, [parallax] the overscan fraction (0 disables parallax).
     */
    fun frame(
        bitmapW: Int, bitmapH: Int, surfaceW: Int, surfaceH: Int,
        xOffset: Float, zoom: Float, parallax: Float = PARALLAX, zoomRange: Float = ZOOM,
    ): Frame {
        val p = parallax.coerceAtLeast(0f)
        val z = zoom.coerceIn(0f, 1f)
        val scale = cover(bitmapW, bitmapH, surfaceW, surfaceH) * (1f + p) * (1f + zoomRange.coerceAtLeast(0f) * (1f - z))
        val w = max(bitmapW * scale, surfaceW.toFloat())
        val h = max(bitmapH * scale, surfaceH.toFloat())
        // Only the parallax overscan follows the page offset; whatever else is wider than the
        // surface (zoom headroom, aspect mismatch) stays centred, so zoom never shifts the photo.
        val pan = (surfaceW * p).coerceAtMost(w - surfaceW)
        val centred = (w - surfaceW - pan) / 2f
        val dx = (-centred - pan * xOffset.coerceIn(0f, 1f)).coerceIn(surfaceW - w, 0f)
        val dy = (-(h - surfaceH) * 0.5f).coerceIn(surfaceH - h, 0f)
        return Frame(scale, dx, dy)
    }

    /** Decode target: the largest size any frame can need, so one decode serves every offset and zoom. */
    fun decodeTarget(surfaceW: Int, surfaceH: Int, parallax: Float = PARALLAX, zoomRange: Float = ZOOM): Pair<Int, Int> {
        val f = (1f + parallax.coerceAtLeast(0f)) * (1f + zoomRange.coerceAtLeast(0f))
        return ceil(surfaceW * f).toInt().coerceAtLeast(1) to ceil(surfaceH * f).toInt().coerceAtLeast(1)
    }

    /**
     * The size to decode a [imageW] x [imageH] photo at so it just covers [targetW] x [targetH]:
     * never larger than the photo, never smaller than the target on either axis (unless the photo
     * itself is smaller), aspect kept.
     */
    fun decodeSize(imageW: Int, imageH: Int, targetW: Int, targetH: Int): Pair<Int, Int> {
        val scale = maxOf(targetW.toDouble() / imageW, targetH.toDouble() / imageH).coerceAtMost(1.0)
        val w = ceil(imageW * scale).toInt().coerceIn(1, imageW)
        val h = ceil(imageH * scale).toInt().coerceIn(1, imageH)
        return w to h
    }

    /** Cross-fade alpha for progress [t] in 0..1: ease in and out, 0 at the start, 1 at the end. */
    fun fadeAlpha(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

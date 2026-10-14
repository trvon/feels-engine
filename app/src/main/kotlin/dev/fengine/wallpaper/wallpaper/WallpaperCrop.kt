package dev.fengine.wallpaper.wallpaper

/**
 * Pure crop arithmetic for fitting a photo to a display. No Android types, so it is unit- and
 * property-testable on the JVM. The system would centre-crop on its own; this makes the crop
 * explicit per display (phone, tablet, cover screen, inner screen) and lets a focal point
 * (later: a detected face or subject) steer it.
 */
object WallpaperCrop {
    /** Pixel rectangle inside the image; right and bottom are exclusive. */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * The largest rectangle with the target's aspect ratio that fits inside the image, placed so
     * [focalX]/[focalY] (fractions of the image) sit as close to its centre as the edges allow.
     *
     * Invariants: the crop is inside the image, non-empty when both inputs are positive, its aspect
     * matches the target within one pixel of rounding, and it contains the focal point.
     */
    fun fit(imageW: Int, imageH: Int, targetW: Int, targetH: Int, focalX: Float = 0.5f, focalY: Float = 0.5f): Crop {
        require(imageW > 0 && imageH > 0) { "image must have positive size" }
        require(targetW > 0 && targetH > 0) { "target must have positive size" }
        val imageAspect = imageW.toDouble() / imageH
        val targetAspect = targetW.toDouble() / targetH
        val (w, h) = if (imageAspect > targetAspect) {
            // Image is wider than the target: full height, trim the sides.
            val h = imageH
            val w = (h * targetAspect).toInt().coerceIn(1, imageW)
            w to h
        } else {
            val w = imageW
            val h = (w / targetAspect).toInt().coerceIn(1, imageH)
            w to h
        }
        val fx = focalX.coerceIn(0f, 1f) * imageW
        val fy = focalY.coerceIn(0f, 1f) * imageH
        val left = (fx - w / 2.0).toInt().coerceIn(0, imageW - w)
        val top = (fy - h / 2.0).toInt().coerceIn(0, imageH - h)
        return Crop(left, top, left + w, top + h)
    }

    /**
     * Decode sample size so the image still covers [targetW] x [targetH] after cropping: the
     * crop's smaller scale factor must stay at or above 1. Returns a power of two.
     */
    fun sampleSize(imageW: Int, imageH: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (imageW / (sample * 2) >= targetW && imageH / (sample * 2) >= targetH) sample *= 2
        return sample
    }
}

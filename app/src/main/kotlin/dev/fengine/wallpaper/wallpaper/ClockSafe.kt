package dev.fengine.wallpaper.wallpaper

/**
 * Keeps faces out from under the lock screen clock. Pure maths, no Android types.
 *
 * No public API says where the lock screen clock is drawn; on Pixel and One UI it sits in the top
 * third ([BAND]). The system already picks a light or dark clock from the wallpaper's colours
 * (SystemUI's region sampler), so readability is its job; covering a face is ours. Given the faces
 * in a photo, this places the visible window so they sit below the band whenever the photo has the
 * room, and leaves the placement alone otherwise.
 */
object ClockSafe {
    /** Fraction of the screen height, from the top, that the clock may cover. */
    const val BAND = 0.33f

    /** A face in image fractions (0..1 on each axis); right and bottom exclusive. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * A face box from what `android.media.FaceDetector` reports, a midpoint and the distance
     * between the eyes (fractions of the image width and height): about 2.2 eye distances wide, 1.3
     * times as tall, centred a little below the eyes.
     */
    fun faceBox(midX: Float, midY: Float, eyeDistance: Float, imageW: Int, imageH: Int): Box {
        val wPx = 2.2f * eyeDistance * imageW
        val hPx = 1.3f * wPx
        val cx = midX * imageW
        val cy = midY * imageH + 0.15f * hPx
        return Box(
            ((cx - wPx / 2) / imageW).coerceIn(0f, 1f),
            ((cy - hPx / 2) / imageH).coerceIn(0f, 1f),
            ((cx + wPx / 2) / imageW).coerceIn(0f, 1f),
            ((cy + hPx / 2) / imageH).coerceIn(0f, 1f),
        )
    }

    /** The smallest box holding every face, or null for none. */
    fun union(faces: List<Box>): Box? =
        if (faces.isEmpty()) null
        else
            Box(
                faces.minOf { it.left },
                faces.minOf { it.top },
                faces.maxOf { it.right },
                faces.maxOf { it.bottom },
            )

    /**
     * Where a window of length [view] starts along content of length [length] ([view] <= [length]),
     * so the span [a, b) sits below the first [band] of the window and inside it. [current] is the
     * start the caller would use without faces; it is kept when it already works or when no start
     * can satisfy both. Always in 0..length-view.
     */
    fun start(length: Float, view: Float, a: Float, b: Float, current: Float, band: Float): Float {
        val max = (length - view).coerceAtLeast(0f)
        val now = current.coerceIn(0f, max)
        if (max == 0f || b <= a) return now
        // Faces below the band: start <= a - band*view. Faces inside the window: start >= b - view.
        val hi = minOf(a - band * view, max)
        val lo = maxOf(b - view, 0f)
        if (lo > hi) return now
        return now.coerceIn(lo, hi)
    }

    /**
     * [WallpaperCrop.fit] for a lock screen: the same size, centred on the faces across, with the
     * faces below the clock band when the photo is tall enough to allow it.
     */
    fun crop(
        imageW: Int,
        imageH: Int,
        targetW: Int,
        targetH: Int,
        faces: List<Box>,
        band: Float = BAND,
    ): WallpaperCrop.Crop {
        val u = union(faces) ?: return WallpaperCrop.fit(imageW, imageH, targetW, targetH)
        val cx = (u.left + u.right) / 2f
        val cy = (u.top + u.bottom) / 2f
        val base = WallpaperCrop.fit(imageW, imageH, targetW, targetH, focalX = cx, focalY = cy)
        val top =
            start(
                    imageH.toFloat(),
                    base.height.toFloat(),
                    u.top * imageH,
                    u.bottom * imageH,
                    base.top.toFloat(),
                    band,
                )
                .toInt()
                .coerceIn(0, imageH - base.height)
        return WallpaperCrop.Crop(base.left, top, base.right, top + base.height)
    }
}

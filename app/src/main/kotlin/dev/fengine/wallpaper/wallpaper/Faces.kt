package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log

/**
 * Frontal faces in a decoded photo, for [ClockSafe]. Uses the framework detector: no dependency, no
 * permission, no network. It wants an RGB_565 bitmap of even width, so it runs on a small copy
 * (faces this small still find the ones a lock screen would cover). Any failure means "no faces",
 * which keeps the plain centred crop.
 */
object Faces {
    private const val TAG = "Faces"
    private const val WIDTH = 480
    private const val MAX = 6

    /**
     * Faces in the photo at [uri], from its own small software decode: cheaper than copying a
     * full-size hardware bitmap down.
     */
    fun detect(context: Context, uri: String): List<ClockSafe.Box> = runCatching {
        PhotoCopies.decode(context, uri) { d, info, _ ->
            val w = info.size.width
            if (w > WIDTH)
                d.setTargetSize(
                    WIDTH,
                    (info.size.height.toLong() * WIDTH / w).toInt().coerceAtLeast(1),
                )
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
        .map { small -> detect(small).also { small.recycle() } }
        .onFailure { Log.w(TAG, "face decode skipped: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    fun detect(bitmap: Bitmap): List<ClockSafe.Box> = runCatching {
        val w = (minOf(WIDTH, bitmap.width) / 2 * 2).coerceAtLeast(2)
        val h = (bitmap.height.toLong() * w / bitmap.width).toInt().coerceAtLeast(1)
        // A hardware bitmap cannot be scaled straight to 565; copy it to software first.
        val soft =
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        val small = Bitmap.createScaledBitmap(soft, w, h, true)
        val rgb = small.copy(Bitmap.Config.RGB_565, false)
        if (soft !== bitmap) soft.recycle()
        if (small !== soft && small !== rgb) small.recycle()
        try {
            val found = arrayOfNulls<FaceDetector.Face>(MAX)
            val n = FaceDetector(w, h, MAX).findFaces(rgb, found)
            val mid = PointF()
            found
                .take(n)
                .filterNotNull()
                .filter { it.confidence() >= 0.4f }
                .map { f ->
                    f.getMidPoint(mid)
                    ClockSafe.faceBox(mid.x / w, mid.y / h, f.eyesDistance() / w, w, h)
                }
        } finally {
            rgb.recycle()
        }
    }
        .onFailure { Log.w(TAG, "face detection skipped: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())
}

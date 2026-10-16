package dev.fengine.wallpaper.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import java.io.IOException

/**
 * Decodes a persisted photo URI and hands it to WallpaperManager.
 *
 * Platform contract (verified against the API 37 SDK source, see
 * docs/research/android-wallpaper-audit.md):
 * - setBitmap(bitmap, cropHint, allowBackup, which) needs only SET_WALLPAPER (normal permission).
 * - which = FLAG_SYSTEM | FLAG_LOCK writes both screens in one call; the flags can be used alone.
 * - Returns 0 on failure, otherwise the new wallpaper id.
 * - isSetWallpaperAllowed() is false under the DISALLOW_SET_WALLPAPER user restriction.
 */
class WallpaperApplier(private val context: Context) {

    sealed interface Result {
        /** One id per setBitmap call; Samsung foldables get two (inner, cover). */
        data class Applied(val wallpaperIds: List<Int>) : Result
        data class Failed(val reason: String) : Result
        /** The photo cannot be read any more (grant lost, photo deleted): drop it, do not retry. */
        data class Unreadable(val reason: String) : Result
    }

    /**
     * [which] is a WallpaperManager FLAG_* combination; Samsung display bits are added here, or
     * taken from [panels] (4 inner, 16 cover) when the caller wants one panel only.
     */
    fun apply(uri: Uri, which: Int, panels: Int = 0): Result {
        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported) return Result.Failed("wallpaper not supported for this user")
        if (!wm.isSetWallpaperAllowed) return Result.Failed("setting the wallpaper is disallowed by policy")

        val bitmap = try {
            decode(uri)
        } catch (e: Exception) {
            // SecurityException here means the persisted URI grant was lost (e.g. photo deleted or
            // the 5000-grant cap evicted it). The caller should drop the photo and re-prompt.
            Log.w(TAG, "decode failed for $uri", e)
            return if (e is SecurityException || e is java.io.FileNotFoundException) Result.Unreadable(e.javaClass.simpleName)
            else Result.Failed("cannot read photo: ${e.javaClass.simpleName}")
        }

        try {
            val displays = Displays(context)
            val targets = if (panels != 0 && SamsungDisplays.hasDisplayBits()) listOf(which or panels) else SamsungDisplays.expand(which)
            val ids = targets.map { expanded ->
                val target = displays.forWhich(expanded)
                val crop = WallpaperCrop.fit(bitmap.width, bitmap.height, target.width, target.height)
                val hint = Rect(crop.left, crop.top, crop.right, crop.bottom)
                val id = try {
                    wm.setBitmap(bitmap, hint, false, expanded)
                } catch (e: IllegalArgumentException) {
                    // A firmware that rejects vendor display bits: fall back to the plain flags once.
                    Log.w(TAG, "which=$expanded rejected, retrying with $which", e)
                    wm.setBitmap(bitmap, hint, false, which)
                }
                Log.i(TAG, "setBitmap which=$expanded target=${target.width}x${target.height} crop=$crop -> id=$id")
                id
            }
            return if (ids.any { it == 0 }) Result.Failed("WallpaperManager returned 0 for $ids")
            else Result.Applied(ids)
        } catch (e: IOException) {
            Log.w(TAG, "setBitmap failed", e)
            return Result.Failed("setBitmap: ${e.message}")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Downsamples on decode so the photo still covers the largest display after cropping. Keeps a
     * 12 MP photo from becoming a 50 MB bitmap in a background worker, without ever going below
     * the display's own resolution (which the system would then upscale and blur).
     */
    private fun decode(uri: Uri): Bitmap {
        val target = Displays(context).largest
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(WallpaperCrop.sampleSize(info.size.width, info.size.height, target.width, target.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    private companion object {
        const val TAG = "WallpaperApplier"
    }
}

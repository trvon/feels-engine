package dev.fengine.wallpaper.smart

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri

/** The Android half of [Similar]: a photo's hash from its pixels, and when it was taken. */
object Looks {
    fun hash(bitmap: Bitmap): Long {
        val small = bitmap.scale(9, 8)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) small.recycle()
        return Similar.dHash(
            IntArray(72) {
                val c = px[it]
                (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) /
                    1000
            }
        )
    }

    /**
     * When the photo was taken, from the picker's or the library's own column; null when the
     * provider does not say. The picker names it `date_taken_ms`, MediaStore `datetaken`.
     */
    fun takenAt(context: Context, uri: String): Long? {
        for (column in listOf("date_taken_ms", "datetaken")) {
            val v = runCatching {
                context.contentResolver
                    .query(uri.toUri(), arrayOf(column), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            }
                .getOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    fun of(context: Context, uri: String, bitmap: Bitmap) =
        Similar.Look(hash(bitmap), takenAt(context, uri))
}

package dev.fengine.wallpaper.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/** Small decodes for inference: never the full photo. */
object Images {
    fun decode(context: Context, uri: Uri, longEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Exact target: a sample size alone can leave the bitmap up to 2x larger than asked.
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > longEdge) {
                val scale = longEdge.toFloat() / edge
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }
}

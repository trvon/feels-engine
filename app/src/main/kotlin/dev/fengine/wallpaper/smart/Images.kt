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
            val edge = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (edge / (sample * 2) >= longEdge) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }
}

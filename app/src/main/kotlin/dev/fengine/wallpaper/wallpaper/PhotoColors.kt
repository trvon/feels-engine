package dev.fengine.wallpaper.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri

/**
 * A photo's colours from a tiny software decode (about 96 px): cheap, and readable by
 * WallpaperColors, which cannot read the hardware bitmaps the engine draws. Used by the live engine
 * to tell Android its colours (so the phone's Material You palette follows Motion) and by the
 * "Photo colours" backdrop.
 */
object PhotoColors {
    fun of(context: Context, uri: String): WallpaperColors? = runCatching {
        val small =
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            ) { d, info, _ ->
                val s = 96f / maxOf(info.size.width, info.size.height)
                if (s < 1f)
                    d.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1),
                    )
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        WallpaperColors.fromBitmap(small).also { small.recycle() }
    }
        .getOrNull()
}

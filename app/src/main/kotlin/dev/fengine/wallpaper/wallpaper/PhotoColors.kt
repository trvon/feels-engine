package dev.fengine.wallpaper.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import dev.fengine.wallpaper.ui.Palette

/**
 * A photo's colours from a small software decode: cheap, and readable by WallpaperColors, which
 * cannot read the hardware bitmaps the engine draws. Used by the live engine to tell Android its
 * colours (so the phone's Material You palette follows Motion) and by the "Photo colours" backdrop.
 */
object PhotoColors {
    data class Inspection(val colors: WallpaperColors?, val faces: ClockSafe.Box?)

    fun of(context: Context, uri: String): WallpaperColors? =
        inspect(context, uri, needFaces = false).colors

    /**
     * Single small software decode for both Material You colours and optional lock-screen face
     * detection, avoiding redundant image decodes on every photo change.
     */
    fun inspect(context: Context, uri: String, needFaces: Boolean): Inspection = runCatching {
        val targetEdge = if (needFaces) 480f else 96f
        val small =
            PhotoCopies.decode(context, uri) { d, info, _ ->
                val s = targetEdge / maxOf(info.size.width, info.size.height)
                if (s < 1f)
                    d.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1),
                    )
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        try {
            val colors = WallpaperColors.fromBitmap(small)
            val faces = if (needFaces) ClockSafe.union(Faces.detect(small)) else null
            Inspection(colors, faces)
        } finally {
            small.recycle()
        }
    }
        .getOrDefault(Inspection(null, null))

    /**
     * Synthesizes a [WallpaperColors] from a single opaque ARGB seed for locked Material You
     * theming, including light/dark contrast hints on Android 12+.
     */
    fun fromSeed(seedArgb: Int): WallpaperColors {
        val opaque = seedArgb or (0xFF shl 24)
        val primary = Color.valueOf(opaque)
        val lum = Palette.luminance(opaque)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var hints = 0
            if (lum > 0.5) hints = hints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT
            if (lum <= 0.35) hints = hints or WallpaperColors.HINT_SUPPORTS_DARK_THEME
            WallpaperColors(primary, null, null, hints)
        } else {
            WallpaperColors(primary, null, null)
        }
    }
}

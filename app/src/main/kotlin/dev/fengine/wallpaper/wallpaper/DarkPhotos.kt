package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import dev.fengine.wallpaper.data.Brightness
import dev.fengine.wallpaper.smart.PhotoIndex

/**
 * Which photos are dark, for "Darker photos in dark mode". Each photo is measured once from a 32 px
 * software decode and remembered in the photo index as a verdict, so later changes cost a lookup. A
 * change measures at most [budget] new photos; unmeasured ones count as not dark.
 */
class DarkPhotos(
    private val context: Context,
    private val index: PhotoIndex,
    private var budget: Int = 24,
) {
    fun isDark(uri: String): Boolean {
        runCatching { index.verdict(RULE, uri) }
            .getOrNull()
            ?.let {
                return it
            }
        if (budget <= 0) return false
        budget--
        // A photo that cannot be measured counts as not dark, once: it no longer eats the budget.
        val dark = measure(uri) ?: false
        runCatching { index.putVerdict(RULE, uri, dark) }
        return dark
    }

    private fun measure(uri: String): Boolean? = runCatching {
        val small =
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            ) { d, info, _ ->
                val s = EDGE.toFloat() / maxOf(info.size.width, info.size.height)
                if (s < 1f)
                    d.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1),
                    )
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        small.recycle()
        Brightness.isDark(px)
    }
        .getOrNull()

    companion object {
        /** Verdict key; bump the version when the measure changes. */
        const val RULE = "dark:v1"
        private const val EDGE = 32
    }
}

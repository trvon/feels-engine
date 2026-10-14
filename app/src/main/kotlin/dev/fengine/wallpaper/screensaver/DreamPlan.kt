package dev.fengine.wallpaper.screensaver

import kotlin.random.Random

/**
 * The order a screen saver session plays a group in. Sequential play continues after the
 * group's wallpaper cursor so the saver does not replay what the wallpaper just showed; shuffle
 * is a fresh permutation per session. Either way every photo plays once before any repeats.
 */
object DreamPlan {
    fun order(photos: List<String>, shuffle: Boolean, cursor: Int, seed: Long): List<String> {
        if (photos.isEmpty()) return emptyList()
        if (shuffle) return photos.shuffled(Random(seed))
        val start = ((cursor + 1) % photos.size + photos.size) % photos.size
        return photos.drop(start) + photos.take(start)
    }

    /** How long one photo stays, including its fade in. */
    const val PHOTO_MS = 12_000L
    const val FADE_MS = 1_500L
    const val ZOOM_FROM = 1.0f
    const val ZOOM_TO = 1.08f
}

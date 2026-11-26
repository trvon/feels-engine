package dev.fengine.wallpaper.data

/**
 * How the app looks and how photos sit on the wallpaper and screen saver (Settings > Appearance).
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Where the app's colours come from: the current photo, or Android's system palette. */
enum class ColorSource {
    PHOTO,
    PHONE,
}

/** What fills the screen where the photo does not: black, or a dark shade of the photo's colour. */
enum class Backdrop {
    BLACK,
    PHOTO,
}

/** Fill the screen (cropping the photo) or show the whole photo (with a backdrop around it). */
enum class PhotoFit {
    FILL,
    WHOLE,
}

/**
 * How Motion feeds colours to Android's Material You engine (`WallpaperColors`): AUTO follows each
 * photo as it changes; LOCKED keeps a chosen accent colour steady across changes.
 */
enum class DynamicColorMode {
    AUTO,
    LOCKED,
}

object Appearance {
    /** Curated Material You seed presets (opaque ARGB ints) for one-tap locked theming. */
    val COLOR_PRESETS: List<Pair<String, Int>> =
        listOf(
            "Sage" to 0xFF4A6B5D.toInt(),
            "Ocean" to 0xFF2B6CB0.toInt(),
            "Terracotta" to 0xFFB85D3B.toInt(),
            "Plum" to 0xFF7B4B7A.toInt(),
            "Ochre" to 0xFFB5892A.toInt(),
            "Slate" to 0xFF54626F.toInt(),
        )

    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean =
        when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    /**
     * Resolves the ARGB seed colour reported to Material You, or null when the photo's full
     * extracted `WallpaperColors` should be used directly.
     */
    fun lockedWallpaperSeed(mode: DynamicColorMode, pinnedSeed: Int?): Int? =
        if (mode == DynamicColorMode.LOCKED) pinnedSeed?.let { it or (0xFF shl 24) } else null
}

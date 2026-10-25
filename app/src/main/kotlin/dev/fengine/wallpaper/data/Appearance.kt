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

object Appearance {
    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean =
        when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
}

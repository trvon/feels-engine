package dev.fengine.wallpaper.wallpaper

import android.os.Build

/**
 * Samsung One UI extends WallpaperManager's `which` with display-mode bits (decompiled from the
 * Galaxy Z Fold 8 framework, One UI 9 / Android 17, see docs/research/android-wallpaper-audit.md):
 *
 *   FLAG_DISPLAY_PHONE = 4   inner (main) screen
 *   FLAG_DISPLAY_DEX   = 8
 *   FLAG_DISPLAY_SUB   = 16  cover screen
 *   FLAG_DISPLAY_VIRTUAL = 32
 *   FLAG_MODE_MASK = 60, FLAG_TYPE_MASK = 3
 *
 * When an app passes no mode bits, SemWallpaperManagerService.getModeEnsuredWhich() fills them in
 * from the hinge: closed -> SUB (cover), open -> PHONE (inner). So a plain FLAG_SYSTEM|FLAG_LOCK call
 * only ever updates the screen that is currently active. Passing the bits explicitly targets each
 * display, so a "both" rotation needs two calls on Samsung foldables.
 */
object SamsungDisplays {
    const val FLAG_DISPLAY_PHONE = 4
    const val FLAG_DISPLAY_SUB = 16

    val isSamsung: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** True where the display bits mean something (One UI). */
    fun hasDisplayBits(samsung: Boolean = isSamsung): Boolean = samsung

    /** The `which` values to pass to setBitmap, in order, for one logical rotation. */
    fun expand(which: Int, samsung: Boolean = isSamsung): List<Int> =
        if (samsung) listOf(which or FLAG_DISPLAY_PHONE, which or FLAG_DISPLAY_SUB) else listOf(which)
}

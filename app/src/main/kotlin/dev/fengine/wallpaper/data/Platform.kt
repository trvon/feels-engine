package dev.fengine.wallpaper.data

/**
 * Rules that change with the Android version, as pure functions of the SDK level and what the
 * framework answered. The Android classes read the inputs and act on the answer; the rules are
 * property-tested across every SDK from minSdk up (`invariants/PlatformProperties`).
 * Levels: P 28, Q 29, R 30, T 33, U 34.
 */
object Platform {
    const val MIN_SDK = 28

    /**
     * Whether the live engine draws the lock screen.
     *
     * Android 14+ answers per screen: [lockComponentIsOurs] is non-null when the lock screen has a
     * live wallpaper of its own. With no answer (null), or before Android 14 where the question
     * cannot be asked, a live home wallpaper also covers the lock screen unless the lock screen
     * has its own still wallpaper ([lockWallpaperId] > 0, `getWallpaperId(FLAG_LOCK)`, API 24).
     */
    fun lockIsLive(sdk: Int, homeIsLive: Boolean, lockComponentIsOurs: Boolean?, lockWallpaperId: Int): Boolean =
        if (sdk >= 34 && lockComponentIsOurs != null) lockComponentIsOurs
        else homeIsLive && lockWallpaperId <= 0

    /**
     * Whether the live engine draws the home screen.
     *
     * [infoIsOurs]: `getWallpaperInfo` named our component. Before Android 14 that call needs
     * `QUERY_ALL_PACKAGES` (WallpaperManager javadoc) and Android 13 enforces it even for the
     * app's own wallpaper, returning null while the engine runs (API 33 emulator, 2026-10-15).
     * So below 14 a running, non-preview engine in this process also counts: while it is the
     * wallpaper, the system keeps the process bound to it. From Android 14 the answer is reliable.
     */
    fun homeIsLive(sdk: Int, infoIsOurs: Boolean, engineRunning: Boolean): Boolean =
        infoIsOurs || (sdk < 34 && engineRunning)

    /**
     * Persisted URI grants Android keeps per app before it silently drops the oldest
     * (`UriGrantsManagerService.MAX_PERSISTED_URI_GRANTS`: 128, raised to 512 in Android 11).
     */
    fun grantCeiling(sdk: Int): Int = if (sdk >= 30) 512 else 128

    /** When the app starts warning: early enough to act before grants are dropped. */
    fun grantWarnAt(sdk: Int): Int = grantCeiling(sdk) * 4 / 5

    /**
     * The MediaStore column that holds a photo's folder. `RELATIVE_PATH` exists from Android 10;
     * Android 9 rejects a query that names it, so it falls back to the full file path (`_data`).
     */
    fun folderColumn(sdk: Int): String = if (sdk >= 29) "relative_path" else "_data"

    /** Screenshots stay out of described groups. Works on a relative path or a full file path. */
    fun isScreenshot(folder: String?): Boolean =
        folder.orEmpty().split('/').any { it.equals("Screenshots", ignoreCase = true) }
}

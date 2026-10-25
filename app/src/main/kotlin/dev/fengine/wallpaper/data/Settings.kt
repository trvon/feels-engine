package dev.fengine.wallpaper.data

/** How the next change is triggered. */
enum class Mode {
    /** Every N minutes through WorkManager periodic work (15 min floor). */
    INTERVAL,
    /** At fixed clock times through inexact AlarmManager alarms. */
    TIME_OF_DAY,
}

data class Settings(
    val groups: List<Group> = emptyList(),
    /** Group that feeds the home screen, or null for "leave alone". */
    val homeGroupId: String? = null,
    /** Group that feeds the lock screen, or null for "leave alone". */
    val lockGroupId: String? = null,
    val mode: Mode = Mode.INTERVAL,
    /** INTERVAL mode period. Clamped to the WorkManager floor when scheduled. */
    val intervalMinutes: Int = 60,
    /** TIME_OF_DAY mode slots as minutes after local midnight, sorted. */
    val timesOfDay: List<Int> = listOf(7 * 60, 19 * 60),
    val shuffle: Boolean = false,
    val enabled: Boolean = false,
    val lastResult: String = "",
    /** Epoch millis of the last photo change on any screen; 0 before the first. */
    val lastRotationAt: Long = 0,
    /** Group the screen saver plays, or null for the home group. */
    val dreamGroupId: String? = null,
    /**
     * On a foldable, the group the cover screen shows instead of the inner screen's; null for the
     * same.
     */
    val coverGroupId: String? = null,
    /**
     * Screen saver plays only on wireless charging or a dock, so a car's USB charger never starts
     * it. On for new installs (NHTSA asks portable devices not to show photographic images while
     * driving; docs/product/2026-10-15-product-review.md). Installs from before the setting existed
     * keep their behaviour: see SettingsCodec.
     */
    val dreamWirelessOnly: Boolean = true,
    /** Settings > Appearance. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.PHOTO,
    val backdrop: Backdrop = Backdrop.BLACK,
    val photoFit: PhotoFit = PhotoFit.FILL,
    /** The live engine was drawing a screen when last checked (MotionWatch). */
    val motionOn: Boolean = false,
    /** When Motion stopped without the owner choosing Still; 0 for no notice. */
    val motionLostAt: Long = 0,
    /** Double-tapping empty home screen space shows the next photo (needs Motion). */
    val tapToChange: Boolean = false,
    /** Switching between light and dark shows the next photo (needs Motion). */
    val changeOnTheme: Boolean = false,
    /** While the phone is dark, changes prefer the group's darker photos (DarkPhotos). */
    val darkAtNight: Boolean = false,
) {
    fun group(id: String?): Group? = id?.let { g -> groups.firstOrNull { it.id == g } }

    val homeGroup: Group?
        get() = group(homeGroupId)

    val lockGroup: Group?
        get() = group(lockGroupId)

    val dreamGroup: Group?
        get() = group(dreamGroupId)

    val coverGroup: Group?
        get() = group(coverGroupId)

    /** True when at least one screen has a group with photos: the precondition for rotating. */
    val canRotate: Boolean
        get() = listOfNotNull(homeGroup, lockGroup).any { it.allPhotos.isNotEmpty() }

    val hasDescribedGroups: Boolean
        get() = groups.any { it.hasDescription }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
    }
}

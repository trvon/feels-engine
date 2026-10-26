package dev.fengine.wallpaper.screensaver

/**
 * Whether the screen saver should play right now. Android starts it on its own conditions
 * (charging, docked, upright); this is the app's veto, pure so it is property-tested.
 *
 * - Car mode (Android Auto and car docks switch the phone to `UI_MODE_TYPE_CAR`): never.
 * - "Wireless charging or a dock only": a car's USB charger, or any cable, does not count.
 *
 * Plug values are `BatteryManager.BATTERY_PLUGGED_*`: AC 1, USB 2, WIRELESS 4, DOCK 8.
 */
object DreamGate {
    const val PLUGGED_WIRELESS = 4
    const val PLUGGED_DOCK = 8

    enum class Verdict {
        PLAY,
        SKIP_CAR,
        SKIP_WIRED,
        SKIP_EMPTY,
    }

    /**
     * What a start does. A veto ends the screen saver ([END]): stock Android then puts the phone to
     * sleep as if there were no screen saver. Staying black instead kept the screen on, dreaming,
     * for as long as the cable was in (fe_phone, Android 16, 2026-10-25). But the owner's Fold
     * restarted an ended screen saver about every 5 seconds (184 restarts in 16 minutes), so a veto
     * within [RESTART_WINDOW_MS] of the last one stays black at minimum brightness ([BLACK]).
     */
    enum class Show {
        PHOTOS,
        END,
        BLACK,
    }

    const val RESTART_WINDOW_MS = 60_000L

    /** [lastVetoAt]: when the previous start was vetoed, or null for none this process. */
    fun show(v: Verdict, lastVetoAt: Long? = null, now: Long = 0): Show =
        when {
            v == Verdict.PLAY -> Show.PHOTOS
            lastVetoAt != null && now - lastVetoAt in 0 until RESTART_WINDOW_MS -> Show.BLACK
            else -> Show.END
        }

    fun decide(carMode: Boolean, plugged: Int, wirelessOnly: Boolean, hasPhotos: Boolean): Verdict =
        when {
            carMode -> Verdict.SKIP_CAR
            wirelessOnly && plugged and (PLUGGED_WIRELESS or PLUGGED_DOCK) == 0 ->
                Verdict.SKIP_WIRED
            !hasPhotos -> Verdict.SKIP_EMPTY
            else -> Verdict.PLAY
        }
}

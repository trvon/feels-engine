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

    enum class Verdict { PLAY, SKIP_CAR, SKIP_WIRED, SKIP_EMPTY }

    fun decide(carMode: Boolean, plugged: Int, wirelessOnly: Boolean, hasPhotos: Boolean): Verdict = when {
        carMode -> Verdict.SKIP_CAR
        wirelessOnly && plugged and (PLUGGED_WIRELESS or PLUGGED_DOCK) == 0 -> Verdict.SKIP_WIRED
        !hasPhotos -> Verdict.SKIP_EMPTY
        else -> Verdict.PLAY
    }
}

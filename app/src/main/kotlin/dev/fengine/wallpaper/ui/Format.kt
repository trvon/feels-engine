package dev.fengine.wallpaper.ui

import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import java.text.DateFormat
import java.util.Date

/** Copy helpers: the schedule reads as a sentence, never as a form. */
object Format {
    fun clock(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

    fun schedule(s: Settings): String =
        when (s.mode) {
            Mode.INTERVAL ->
                when {
                    s.intervalMinutes % 1440 == 0 -> plural(s.intervalMinutes / 1440, "day")
                    s.intervalMinutes % 60 == 0 -> plural(s.intervalMinutes / 60, "hour")
                    else -> "Every ${s.intervalMinutes} minutes"
                }
            Mode.TIME_OF_DAY -> "At " + s.timesOfDay.joinToString(" and ", transform = ::clock)
        }

    /** The Home notice after Android turned Motion off (MotionWatch). */
    fun motionLost(atMs: Long): String =
        "Motion stopped on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))}. " +
            "Another wallpaper replaced it, or Android turned it off after the app was stopped " +
            "or reinstalled. Photos keep changing as stills until you turn it back on."

    private fun plural(n: Int, unit: String) = if (n == 1) "Every $unit" else "Every $n ${unit}s"

    fun status(s: Settings): String {
        val home = s.homeGroup?.takeIf { it.allPhotos.isNotEmpty() }
        val lock = s.lockGroup?.takeIf { it.allPhotos.isNotEmpty() }
        return when {
            s.groups.isEmpty() -> "Start with a group of photos."
            home == null && lock == null -> "Choose a group for your screens."
            !s.enabled -> "Paused."
            home != null && home.id == lock?.id -> "${schedule(s)}, from ${home.name}."
            home != null && lock != null ->
                "${schedule(s)}. Home from ${home.name}, lock from ${lock.name}."
            home != null -> "${schedule(s)}, home screen from ${home.name}."
            else -> "${schedule(s)}, lock screen from ${lock!!.name}."
        }
    }
}

/** "1 photo", "14 photos". */
fun photos(n: Int): String = if (n == 1) "1 photo" else "$n photos"

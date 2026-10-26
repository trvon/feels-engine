package dev.fengine.wallpaper.ui

import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import java.text.DateFormat
import java.util.Date

/** Copy helpers: the schedule reads as a sentence, never as a form. */
object Format {
    /** "19:00", or "7:00 PM" when the phone uses a 12-hour clock ([is24] false). */
    fun clock(minutes: Int, is24: Boolean = true): String {
        val h = minutes / 60
        val m = minutes % 60
        if (is24) return "%d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }

    fun schedule(s: Settings, is24: Boolean = true): String =
        when (s.mode) {
            Mode.INTERVAL -> every(s.intervalMinutes)
            Mode.TIME_OF_DAY -> "At " + s.timesOfDay.joinToString(" and ") { clock(it, is24) }
        }

    /** "Every 15 minutes", "Every 2 hours", "Every 90 minutes", "Every week". */
    fun every(minutes: Int): String =
        when {
            minutes == 10080 -> "Every week"
            minutes % 1440 == 0 -> plural(minutes / 1440, "day")
            minutes % 60 == 0 -> plural(minutes / 60, "hour")
            else -> "Every $minutes minutes"
        }

    /** The Home notice after Android turned Motion off (MotionWatch). */
    fun motionLost(atMs: Long): String =
        "Motion stopped on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))}. " +
            "Another wallpaper replaced it, or Android turned it off after the app was stopped " +
            "or reinstalled. Photos keep changing as stills until you turn it back on."

    private fun plural(n: Int, unit: String) = if (n == 1) "Every $unit" else "Every $n ${unit}s"

    fun status(s: Settings, is24: Boolean = true): String {
        val home = s.homeGroup?.takeIf { it.allPhotos.isNotEmpty() }
        val lock = s.lockGroup?.takeIf { it.allPhotos.isNotEmpty() }
        return when {
            s.groups.isEmpty() -> "Start with a group of photos."
            home == null && lock == null -> "Choose a group for your screens."
            !s.enabled -> "Paused."
            home != null && home.id == lock?.id -> "${schedule(s, is24)}, from ${home.name}."
            home != null && lock != null ->
                "${schedule(s, is24)}. Home from ${home.name}, lock from ${lock.name}."
            home != null -> "${schedule(s, is24)}, home screen from ${home.name}."
            else -> "${schedule(s, is24)}, lock screen from ${lock!!.name}."
        }
    }
}

/** "1 photo", "14 photos". */
fun photos(n: Int): String = if (n == 1) "1 photo" else "$n photos"

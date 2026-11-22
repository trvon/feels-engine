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
    /** Storage in plain units: "850 KB", "48 MB", "1.2 GB". */
    fun megabytes(bytes: Long): String =
        when {
            bytes < 1_000_000 -> "${(bytes + 999) / 1000} KB"
            bytes < 1_000_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
            else -> "%.1f GB".format(java.util.Locale.US, bytes / 1e9)
        }

    fun every(minutes: Int): String =
        when {
            minutes == 10080 -> "Every week"
            minutes % 1440 == 0 -> plural(minutes / 1440, "day")
            minutes % 60 == 0 -> plural(minutes / 60, "hour")
            else -> "Every $minutes minutes"
        }

    /** The Home notice after Android turned Motion off (MotionWatch). */
    /** Home notice for picked photos no group refers to any more. */
    fun orphanPhotos(n: Int): String =
        (if (n == 1) "1 photo you picked was" else "$n photos you picked were") +
            " taken out of ${if (n == 1) "its group" else "their groups"} after a failed read. " +
            "${if (n == 1) "It" else "They"} can go back in."

    fun motionLost(atMs: Long): String =
        "Motion stopped on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))}. " +
            "Another wallpaper replaced it, or Android turned it off after the app was stopped " +
            "or reinstalled. Photos keep changing as stills until you turn it back on."

    private fun plural(n: Int, unit: String) = if (n == 1) "Every $unit" else "Every $n ${unit}s"

    /**
     * When something happened, relative to [now]: "at 14:02" today, "yesterday at 14:02", "on 12
     * Nov at 14:02" before that. A bare time never stands for another day.
     */
    fun whenAt(
        atMs: Long,
        now: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        is24: Boolean = true,
    ): String {
        val at = java.time.Instant.ofEpochMilli(atMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time =
            at.format(
                java.time.format.DateTimeFormatter.ofPattern(
                    if (is24) "HH:mm" else "h:mm a",
                    java.util.Locale.US,
                )
            )
        val day = at.toLocalDate()
        return when (day) {
            today -> "at $time"
            today.minusDays(1) -> "yesterday at $time"
            else ->
                "on ${at.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US))} at $time"
        }
    }

    /** "2 days ago", "5 hours ago", "40 minutes ago". */
    fun ago(atMs: Long, now: Long): String {
        val m = ((now - atMs) / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> if (m == 1L) "1 minute ago" else "$m minutes ago"
            m < 48 * 60 -> (m / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            else -> "${m / (24 * 60)} days ago"
        }
    }

    /**
     * Home's caption under the headline: the last result with its day, so a change from three days
     * ago never reads as current.
     */
    fun lastResult(s: Settings, now: Long, is24: Boolean = true): String? {
        val r =
            s.lastResult.ifBlank {
                return null
            }
        return when {
            r.startsWith("Changed") && s.lastRotationAt > 0 ->
                "Changed ${whenAt(s.lastRotationAt, now, is24 = is24)}"
            r.startsWith("Problem: ") && s.lastAttemptAt > 0 ->
                "Problem ${whenAt(s.lastAttemptAt, now, is24 = is24)}: ${r.removePrefix("Problem: ")}"
            else -> r
        }
    }

    fun status(s: Settings, is24: Boolean = true): String {
        val home = s.homeGroup?.takeIf { it.allPhotos.isNotEmpty() }
        val lock = s.lockGroup?.takeIf { it.allPhotos.isNotEmpty() }
        return when {
            s.groups.isEmpty() -> "Start with a group of photos."
            home == null && lock == null ->
                // A screen can have a group that has no photos yet: say what is missing.
                (s.homeGroup ?: s.lockGroup)?.let { g ->
                    if (g.hasDescription) "${g.name} is waiting for photos to look through."
                    else "Add photos to ${g.name} to start."
                } ?: "Choose a group for your screens."
            !s.enabled && s.lastRotationAt <= 0 -> "Not started."
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

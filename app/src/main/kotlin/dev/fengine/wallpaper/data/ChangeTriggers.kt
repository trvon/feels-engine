package dev.fengine.wallpaper.data

import kotlin.math.abs

/**
 * When a photo changes besides the schedule: a double tap on the home screen and a switch between
 * light and dark, both delivered to the live engine; and the steps the interval slider offers.
 * Pure.
 */
object ChangeTriggers {
    /**
     * Second tap within this long after the first (ViewConfiguration's double-tap timeout is 300).
     */
    const val DOUBLE_TAP_MS = 400L

    data class Tap(val atMs: Long, val x: Int, val y: Int)

    /**
     * True when [now] completes a double tap after [prev]: soon enough and within [slopPx] of it.
     * Launchers send `WallpaperManager.COMMAND_TAP` for taps on empty home screen space only.
     */
    fun isDoubleTap(prev: Tap?, now: Tap, slopPx: Int): Boolean =
        prev != null &&
            now.atMs - prev.atMs in 1..DOUBLE_TAP_MS &&
            abs(now.x - prev.x) <= slopPx &&
            abs(now.y - prev.y) <= slopPx

    private const val NIGHT_MASK = 0x30 // Configuration.UI_MODE_NIGHT_MASK

    /**
     * True when the night bits of `Configuration.uiMode` changed; null [prev] is the first look.
     */
    fun nightChanged(prev: Int?, uiMode: Int): Boolean =
        prev != null && (prev and NIGHT_MASK) != (uiMode and NIGHT_MASK)

    /** Interval slider stops, in minutes: 15 minutes (the WorkManager floor) to a week. */
    val INTERVAL_STOPS =
        listOf(15, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440, 2880, 4320, 10080)

    /** The stop closest to [minutes], so any stored interval lands on the slider. */
    fun nearestStop(minutes: Int): Int =
        INTERVAL_STOPS.indices.minBy { abs(INTERVAL_STOPS[it] - minutes) }
}

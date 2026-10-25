package dev.fengine.wallpaper.data

/**
 * Notices when Motion stops without the owner choosing Still. Android reverts a live wallpaper to
 * its built-in one when the app is reinstalled, force stopped, or its process dies twice within 10
 * s (WallpaperManagerService), and only a person can turn it back on through the system chooser. So
 * the app remembers that the engine was on and says so when it is gone. Pure.
 */
object MotionWatch {
    data class State(val on: Boolean, val lostAt: Long)

    /**
     * Whether a "not live" answer can be believed. Android 13 hides the app's own wallpaper from
     * `getWallpaperInfo` (Platform.homeIsLive), so there "off" may only mean the engine has not
     * attached to this process yet.
     */
    fun canSeeOff(sdk: Int): Boolean = sdk != 33

    /** The next state after seeing whether the engine draws any screen now ([live]). */
    fun observe(prev: State, live: Boolean, sdk: Int, now: Long): State =
        when {
            live -> State(on = true, lostAt = 0)
            !canSeeOff(sdk) -> prev
            prev.on -> State(on = false, lostAt = now)
            else -> prev
        }

    /** The owner chose Still: off, and nothing was lost. */
    fun still(): State = State(on = false, lostAt = 0)

    /** True when [observe] just turned [prev] into a loss. */
    fun justLost(prev: State, next: State): Boolean = prev.on && !next.on && next.lostAt > 0
}

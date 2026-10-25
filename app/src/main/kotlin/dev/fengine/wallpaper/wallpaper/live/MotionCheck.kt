package dev.fengine.wallpaper.wallpaper.live

import android.os.Build
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.MotionWatch

/** Applies [MotionWatch] to what the system reports; a loss is recorded once in the ledger. */
object MotionCheck {
    suspend fun observe(app: FengineApp, live: LiveWallpaper.State = LiveWallpaper.state(app)) {
        val any = live.innerHome || live.innerLock || live.coverHome || live.coverLock
        val now = System.currentTimeMillis()
        var lost = false
        app.settings.update { s ->
            val prev = MotionWatch.State(s.motionOn, s.motionLostAt)
            val next = MotionWatch.observe(prev, any, Build.VERSION.SDK_INT, now)
            lost = MotionWatch.justLost(prev, next)
            if (next == prev) s else s.copy(motionOn = next.on, motionLostAt = next.lostAt)
        }
        if (lost) LedgerStore(app).record(Ledger.Event.MotionLost, now)
    }

    /** The owner chose Still. */
    suspend fun still(app: FengineApp) =
        app.settings.update { it.copy(motionOn = false, motionLostAt = 0) }

    /** The owner read the notice and does not want Motion back. */
    suspend fun dismiss(app: FengineApp) = app.settings.update { it.copy(motionLostAt = 0) }
}

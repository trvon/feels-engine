package dev.fengine.wallpaper.wallpaper.live

/**
 * The live engine's remaining decisions, pure so they are property-tested
 * (`invariants/EngineRulesProperties`). `PhotoWallpaperService` holds the Android objects and does
 * what these say; it decides nothing itself.
 */

/**
 * Which bitmaps the engine holds and which it must release. [T] is the bitmap handle (an Android
 * Bitmap in the engine, an Int in tests). Every handle that leaves both slots is returned exactly
 * once for recycling, and a handle still in a slot is never returned: drawing a recycled bitmap
 * crashes, keeping one leaks a full-screen buffer.
 */
class BitmapSlots<T : Any> {
    var current: T? = null
        private set

    var previous: T? = null
        private set

    /**
     * A decode arrived. With [fade], the old photo stays as [previous] while the new one fades in.
     */
    fun onDecoded(new: T, fade: Boolean): List<T> {
        val release = ArrayList<T>(2)
        if (fade) {
            previous?.let(release::add)
            previous = current
        } else {
            previous?.let(release::add)
            current?.let(release::add)
            previous = null
        }
        current = new
        return release.filter { it !== current && it !== previous }
    }

    /** The fade ended or was cancelled: the old photo is no longer drawn. */
    fun onFadeFinished(): List<T> = listOfNotNull(previous).also { previous = null }

    /**
     * Replaces each held bitmap with [convert]'s result (a software copy of a hardware bitmap once
     * the engine falls back to a software canvas). Returns the originals that were replaced.
     */
    fun convert(convert: (T) -> T): List<T> {
        val release = ArrayList<T>(2)
        current?.let { c ->
            convert(c).let {
                if (it !== c) {
                    release += c
                    current = it
                }
            }
        }
        previous?.let { p ->
            convert(p).let {
                if (it !== p) {
                    release += p
                    previous = it
                }
            }
        }
        return release
    }

    /** The engine is going away. */
    fun onDestroy(): List<T> =
        listOfNotNull(previous, current).also {
            previous = null
            current = null
        }
}

/**
 * Hardware canvas until it misbehaves. Two consecutive posts slower than [slowMs] (a GPU stall,
 * seen on emulators) or a hardware canvas that cannot be locked switch the engine to the software
 * canvas for the rest of its life.
 */
class CanvasPolicy(private val slowMs: Long = 500) {
    var softwareOnly = false
        private set

    private var slowPosts = 0

    /** Returns true when this post switched the engine to software. */
    fun onPost(ms: Long): Boolean {
        if (softwareOnly) return false
        if (ms > slowMs) slowPosts++ else slowPosts = 0
        if (slowPosts >= 2) {
            softwareOnly = true
            return true
        }
        return false
    }

    fun onHardwareUnavailable() {
        softwareOnly = true
    }
}

/**
 * Fade frame pacing: at most one frame per [minFrameMs] (60 fps is enough for a 900 ms fade), the
 * last one always.
 */
class FadeClock(private val minFrameMs: Long = 15) {
    private var last = Long.MIN_VALUE / 2

    fun reset() {
        last = Long.MIN_VALUE / 2
    }

    fun shouldDraw(t: Float, now: Long): Boolean {
        if (t < 1f && now - last < minFrameMs) return false
        last = now
        return true
    }
}

/**
 * Engine health from the engine's own point of view, for the local ledger. A screen is "shown" when
 * the system shows the surface (visibility or a redraw request). It ends in exactly one of
 * [Record.FirstFrame] (a frame was drawn, with the latency) or [Record.NoFrame] (it was hidden
 * again with nothing drawn: the black inner screen of 0.2.0). A screen hidden while its last frame
 * was a half-finished cross-fade is a [Record.AbandonedBlend].
 */
class FrameHealth(private val minBlankMs: Long = 1_000) {
    sealed interface Record {
        data class FirstFrame(val latencyMs: Long) : Record

        data class NoFrame(val shownMs: Long) : Record

        data object AbandonedBlend : Record
    }

    private var shownAt: Long? = null
    private var lastBlended = false

    fun shown(now: Long) {
        if (shownAt == null) shownAt = now
    }

    fun drew(now: Long, blended: Boolean): Record? {
        lastBlended = blended
        val at = shownAt ?: return null
        shownAt = null
        return Record.FirstFrame((now - at).coerceAtLeast(0))
    }

    fun hidden(now: Long): List<Record> {
        val out = ArrayList<Record>(2)
        // A screen shown for under [minBlankMs] without a frame is a transition (the chooser
        // closing, a quick visibility flip), not the black screen this record exists to catch.
        shownAt?.let { if (now - it >= minBlankMs) out += Record.NoFrame(now - it) }
        if (lastBlended) out += Record.AbandonedBlend
        shownAt = null
        lastBlended = false
        return out
    }
}

package dev.fengine.wallpaper.wallpaper.live

/**
 * The live engine's decisions, free of Android types: which event leads to a draw, a decode or a
 * fade. [PhotoWallpaperService] feeds it events and performs the I/O it asks for. Keeping the
 * rules here is what lets the invariant suite prove "zero frames at rest" instead of measuring it.
 *
 * Battery rule: nothing is decoded for a screen nobody sees. A photo change that arrives while the
 * wallpaper is invisible (screen off, another app in front) is remembered and decoded when the
 * wallpaper becomes visible again, so eight hours of hourly changes overnight cost one decode.
 */
class LiveEngineState {
    /** What the engine should do in response to one event. */
    data class Decision(
        val draw: Boolean = false,
        /** Decode this URI for the current surface. */
        val decode: String? = null,
        /** The decode replaces the photo without a fade (surface resize), so the old bitmap is just dropped. */
        val keepOld: Boolean = false,
        val startFade: Boolean = false,
        val cancelFade: Boolean = false,
    ) {
        companion object { val NONE = Decision() }
    }

    var width = 0; private set
    var height = 0; private set
    var visible = false; private set
    var xOffset = 0.5f; private set
    var zoom = 0f; private set
    /** The photo the screen should show; null for nothing. */
    var wanted: String? = null; private set
    /** The photo currently decoded for this surface size, or null. */
    var shown: String? = null; private set
    var hasPrevious = false; private set
    var fading = false; private set
    /** True when the last state change happened while invisible and nothing has been drawn since. */
    var dirty = true; private set
    /** A decode that is owed but deferred because nobody can see the result yet. */
    var pendingDecode: Boolean = false; private set
    /** The photo a decode was issued for and has not come back yet. */
    var requested: String? = null; private set

    fun onSurface(w: Int, h: Int): Decision {
        if (w == width && h == height) return Decision.NONE
        width = w; height = h
        // Everything decoded so far, or in flight, is for the old size.
        shown = null; requested = null
        return decodeWanted(keepOld = true)
    }

    /** Decode the wanted photo now if the screen is visible, otherwise owe it until it is. */
    private fun decodeWanted(keepOld: Boolean): Decision {
        val uri = wanted ?: return Decision.NONE
        if (width == 0 || height == 0) return Decision.NONE
        if (shown == uri) { pendingDecode = false; return Decision.NONE } // already decoded for this surface
        if (!visible) { pendingDecode = true; return Decision.NONE }
        pendingDecode = false
        if (requested == uri) return Decision.NONE // already in flight for this surface; its result will draw
        requested = uri
        return Decision(decode = uri, keepOld = keepOld)
    }

    fun onOffset(x: Float): Decision {
        if (x == xOffset) return Decision.NONE
        xOffset = x
        return drawOrMarkDirty()
    }

    fun onZoom(z: Float): Decision {
        if (z == zoom) return Decision.NONE
        zoom = z
        return drawOrMarkDirty()
    }

    fun onVisibility(v: Boolean): Decision {
        if (v == visible) return Decision.NONE
        visible = v
        if (!v) {
            // Never animate for a screen nobody sees; the fade ends where it is.
            val cancel = fading
            fading = false; hasPrevious = false
            return Decision(cancelFade = cancel)
        }
        // Visible again: a deferred decode comes first (its result draws), else a dirty redraw.
        if (pendingDecode) { val d = decodeWanted(keepOld = shown == null); if (d != Decision.NONE) return d }
        return if (dirty) draw() else Decision.NONE
    }

    /** The settings say this screen should show [uri] (or nothing). */
    fun onPhoto(uri: String?): Decision {
        if (uri == wanted) return Decision.NONE
        wanted = uri
        if (uri == null) { shown = null; hasPrevious = false; fading = false; pendingDecode = false; requested = null; return drawOrMarkDirty() }
        return decodeWanted(keepOld = false)
    }

    /** A decode finished. Stale results (for a photo no longer wanted) are dropped. */
    fun onDecoded(uri: String, keepOld: Boolean): Decision {
        if (uri == requested) requested = null
        if (uri != wanted) return Decision.NONE
        val hadPhoto = shown != null && !keepOld
        shown = uri
        if (hadPhoto && visible) {
            fading = true; hasPrevious = true
            return Decision(startFade = true)
        }
        hasPrevious = false; fading = false
        return drawOrMarkDirty()
    }

    /** One fade frame at progress [t] in 0..1. */
    fun onFadeFrame(t: Float): Decision {
        if (!fading) return Decision.NONE
        if (t >= 1f) { fading = false; hasPrevious = false }
        return if (visible) draw() else { dirty = true; Decision.NONE }
    }

    private fun drawOrMarkDirty(): Decision = if (visible) draw() else { dirty = true; Decision.NONE }
    private fun draw(): Decision { dirty = false; return Decision(draw = true) }
}

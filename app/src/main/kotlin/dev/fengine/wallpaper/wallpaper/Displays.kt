package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Physical display sizes this wallpaper has to fit. Phones and tablets have one; Samsung
 * foldables expose the inner and cover panels as two displays, and the `which` display bits
 * decide which one a write targets.
 */
class Displays(context: Context) {
    data class Target(val width: Int, val height: Int) {
        /** Portrait-first: wallpapers are set for the taller orientation and parallax the rest. */
        val portrait: Target get() = if (width <= height) this else Target(height, width)
    }

    private val all: List<Target> = run {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // While a Samsung foldable is closed, the inner panel is in no public list (not even the
        // presentation category, verified on the Fold 8, One UI 9), but getDisplay(id) still answers.
        // Whatever this run sees is remembered, so a phone that once ran unfolded knows its inner panel.
        val seen = (dm.displays.toList() + dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) +
            PROBE_IDS.mapNotNull { dm.getDisplay(it) })
            .filter { it.isValid }
            .distinctBy { it.displayId }
        val now = seen.map { d ->
            val p = Point(); @Suppress("DEPRECATION") d.getRealSize(p)
            Target(p.x, p.y)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getStringSet(KEY_PANELS, emptySet()).orEmpty().mapNotNull(::parse)
        val panels = (now.map { it.portrait } + remembered).distinct()
        prefs.edit().putStringSet(KEY_PANELS, panels.map { "${it.width}x${it.height}" }.toSet()).apply()
        Log.i(TAG, "displays now: " + seen.zip(now).joinToString { (d, t) -> "${d.displayId}=${t.width}x${t.height}" } +
            "; panels: " + panels.joinToString { "${it.width}x${it.height}" })
        panels.ifEmpty { listOf(Target(1080, 2400)) }
    }

    /** Largest panel (inner screen on a foldable, the only one elsewhere). */
    val largest: Target = all.maxBy { it.width.toLong() * it.height }.portrait

    /** Smallest panel (cover screen on a foldable). */
    val smallest: Target = all.minBy { it.width.toLong() * it.height }.portrait

    /** The panel a Samsung `which` targets: cover for the SUB bit, inner otherwise. */
    fun forWhich(which: Int): Target =
        if (which and SamsungDisplays.FLAG_DISPLAY_SUB != 0) smallest else largest

    companion object {
        private const val TAG = "Displays"
        private const val PREFS = "displays"
        private const val KEY_PANELS = "panels"
        private val PROBE_IDS = 0..3

        private fun parse(s: String): Target? {
            val (w, h) = s.split('x').map { it.toIntOrNull() ?: return null }.takeIf { it.size == 2 } ?: return null
            return Target(w, h)
        }

        /** Default display only, for callers without a Context-free path. */
        fun of(display: Display): Target { val p = Point(); @Suppress("DEPRECATION") display.getRealSize(p); return Target(p.x, p.y) }
    }
}

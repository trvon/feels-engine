package dev.fengine.wallpaper.data

import org.json.JSONObject

/**
 * The local ledger: what the app did and how the engine behaved, kept on the phone only and
 * exported by the owner (docs/product/2026-10-15-product-review.md, item 2). One JSON object per
 * line. Pure: encoding, decoding and the summary are property-tested; `LedgerStore` writes the file.
 */
object Ledger {
    sealed interface Event {
        /** A rotation attempt for one write (or a live screen that only advanced its cursor). */
        data class Rotation(val source: String, val which: Int, val panels: Int, val result: String) : Event
        /** A shown screen got its first engine frame after [latencyMs]. */
        data class FirstFrame(val latencyMs: Long) : Event
        /** A shown screen was hidden again before any frame: a black screen for [shownMs]. */
        data class NoFrame(val shownMs: Long) : Event
        /** A screen was hidden while its last frame was half of a cross-fade. */
        data object AbandonedBlend : Event
        /** The screen saver started; [verdict] is DreamGate's answer. */
        data class ScreenSaver(val verdict: String, val plugged: Int) : Event
    }

    object Source {
        const val SCHEDULE = "schedule"; const val NEXT = "next"; const val SHOW_NOW = "show-now"
        const val SET_ACTIVE = "set-active"; const val STILL = "still"; const val TILE = "tile"; const val WIDGET = "widget"
        val manual = setOf(NEXT, SHOW_NOW, SET_ACTIVE, TILE, WIDGET)
    }

    data class Entry(val atMs: Long, val event: Event)

    fun encode(e: Entry): String {
        val o = JSONObject().put("t", e.atMs)
        when (val ev = e.event) {
            is Event.Rotation -> o.put("type", "rotation").put("source", ev.source).put("which", ev.which).put("panels", ev.panels).put("result", ev.result)
            is Event.FirstFrame -> o.put("type", "first_frame").put("ms", ev.latencyMs)
            is Event.NoFrame -> o.put("type", "no_frame").put("ms", ev.shownMs)
            Event.AbandonedBlend -> o.put("type", "abandoned_blend")
            is Event.ScreenSaver -> o.put("type", "screen_saver").put("verdict", ev.verdict).put("plugged", ev.plugged)
        }
        return o.toString()
    }

    /** Null for a line this version does not understand (newer type, damaged line): skipped, never fatal. */
    fun decode(line: String): Entry? = runCatching {
        val o = JSONObject(line)
        val t = o.getLong("t")
        val ev: Event = when (o.getString("type")) {
            "rotation" -> Event.Rotation(o.getString("source"), o.getInt("which"), o.getInt("panels"), o.getString("result"))
            "first_frame" -> Event.FirstFrame(o.getLong("ms"))
            "no_frame" -> Event.NoFrame(o.getLong("ms"))
            "abandoned_blend" -> Event.AbandonedBlend
            "screen_saver" -> Event.ScreenSaver(o.getString("verdict"), o.getInt("plugged"))
            else -> return null
        }
        Entry(t, ev)
    }.getOrNull()

    data class Summary(
        val rotations: Int, val failed: Int, val manual: Int,
        val firstFrames: Int, val firstFrameP50: Long?, val firstFrameP95: Long?,
        val noFrame: Int, val abandonedBlends: Int,
        val screenSaver: Map<String, Int>,
    )

    /** Nearest-rank percentile of [sorted] (ascending), [p] in 1..100; null when empty. */
    fun percentile(sorted: List<Long>, p: Int): Long? {
        if (sorted.isEmpty()) return null
        // Integer ceiling: 0.28 * 25 is 7.000000000000001 in floating point, which ranked one too high.
        val rank = ((p.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** What happened in [fromMs, toMs]. */
    fun summarize(entries: List<Entry>, fromMs: Long, toMs: Long): Summary {
        val inWindow = entries.filter { it.atMs in fromMs..toMs }.map { it.event }
        val rot = inWindow.filterIsInstance<Event.Rotation>()
        val ff = inWindow.filterIsInstance<Event.FirstFrame>().map { it.latencyMs }.sorted()
        return Summary(
            rotations = rot.size,
            failed = rot.count { it.result == "failed" },
            manual = rot.count { it.source in Source.manual },
            firstFrames = ff.size,
            firstFrameP50 = percentile(ff, 50),
            firstFrameP95 = percentile(ff, 95),
            noFrame = inWindow.count { it is Event.NoFrame },
            abandonedBlends = inWindow.count { it == Event.AbandonedBlend },
            screenSaver = inWindow.filterIsInstance<Event.ScreenSaver>().groupingBy { it.verdict }.eachCount(),
        )
    }

    /** One sentence for the app: the numbers that would have shown this cycle's bugs. */
    fun sentence(s: Summary): String = buildString {
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        append(n(s.rotations, "change", "changes"))
        if (s.failed > 0) append(", ${s.failed} failed")
        if (s.manual > 0) append(", ${s.manual} by hand")
        if (s.firstFrames > 0) append(". Motion: first frame ${s.firstFrameP50} ms typical, ${s.firstFrameP95} ms slowest 5%")
        if (s.noFrame > 0) append(", " + n(s.noFrame, "blank screen", "blank screens"))
        if (s.abandonedBlends > 0) append(", " + n(s.abandonedBlends, "interrupted fade", "interrupted fades"))
        if (s.screenSaver.isNotEmpty()) append(". Screen saver: " + s.screenSaver.entries.sortedBy { it.key }.joinToString { "${it.key.lowercase().replace('_', ' ')} ${it.value}" })
        append(".")
    }
}

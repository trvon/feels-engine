package dev.fengine.wallpaper.smart

/**
 * Near-duplicate filter for found photos. A description finds every frame of a burst ("Nov 13,
 * 12:26 PM" seven times over), and a wallpaper group of seven copies of one moment is not seven
 * photos. Pure: the time each was taken (one photo per [BURST_MS]) and a 64-bit difference hash
 * ([dHash] of a 9x8 luminance grid) for copies and edits taken at any time; property-tested.
 */
object Similar {
    /** Hashes this close are the same picture, whenever taken (re-saves, edits, crops). */
    const val SAME = 10

    /**
     * Taken this close together, two photos are the same moment whatever their hashes: in a burst
     * of children at a table (Fold 8, 2026-11-20) frames 9 to 44 seconds apart differed by 21 to 42
     * of 64 bits, as far apart as unrelated photos, so the hash cannot tell a burst on its own.
     */
    const val BURST_MS = 60_000L

    data class Look(val hash: Long, val takenAt: Long?)

    /** The difference hash of a 9 wide by 8 high luminance grid, row by row. */
    fun dHash(lum: IntArray): Long {
        require(lum.size == 72) { "9x8 grid" }
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = h shl 1
            if (lum[y * 9 + x] > lum[y * 9 + x + 1]) h = h or 1L
        }
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun alike(a: Look, b: Look): Boolean {
        val d = distance(a.hash, b.hash)
        if (d <= SAME) return true
        val ta = a.takenAt ?: return false
        val tb = b.takenAt ?: return false
        return kotlin.math.abs(ta - tb) <= BURST_MS
    }

    /**
     * [found] in order without the photos alike to one kept before it or to one of [kept] (the
     * group's own photos). A photo whose look is unknown is kept: an unread photo is never dropped
     * as a duplicate of nothing.
     */
    fun distinct(found: List<String>, kept: List<String>, look: (String) -> Look?): List<String> {
        val held = kept.mapNotNullTo(ArrayList(), look)
        val out = ArrayList<String>()
        for (uri in found) {
            val l = look(uri)
            if (l != null && held.any { alike(it, l) }) continue
            out += uri
            if (l != null) held += l
        }
        return out
    }
}

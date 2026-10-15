package dev.fengine.wallpaper.invariants

import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import dev.fengine.wallpaper.schedule.RotationPlan
import net.jqwik.api.Arbitraries
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

/** One rotation: every feeding group advances once, writes never target a live screen, and every write is a real photo. */
class RotationAdvanceProperties {

    @Property(tries = 2000)
    fun advanceIsConsistent(
        @ForAll @Size(max = 6) homePhotos: List<String>, @ForAll @Size(max = 6) lockPhotos: List<String>,
        @ForAll sameGroup: Boolean, @ForAll homeAssigned: Boolean, @ForAll lockAssigned: Boolean,
        @ForAll @IntRange(min = -1, max = 10) homeCursor: Int, @ForAll @IntRange(min = -1, max = 10) lockCursor: Int,
        @ForAll shuffle: Boolean, @ForAll liveHome: Boolean, @ForAll liveLock: Boolean, @ForAll seed: Long,
    ) {
        val photos = mapOf("h" to homePhotos, "l" to (if (sameGroup) homePhotos else lockPhotos))
        val cursors = mapOf("h" to homeCursor, "l" to lockCursor)
        val homeId = if (homeAssigned) "h" else null
        val lockId = if (lockAssigned) (if (sameGroup) "h" else "l") else null
        val out = RotationPlan.advance(homeId, lockId, { photos[it]!! }, { cursors[it]!! }, shuffle, liveHome, liveLock, Random(seed))

        // Only feeding, non-empty groups advance, each exactly once.
        val feeding = listOfNotNull(homeId, lockId).filter { photos[it]!!.isNotEmpty() }.toSet()
        assertEquals(feeding, out.cursors.keys)
        for ((id, idx) in out.cursors) {
            assertTrue(idx in photos[id]!!.indices)
            if (!shuffle) assertEquals((cursors[id]!! + 1).mod(photos[id]!!.size), idx)
            else if (photos[id]!!.size > 1 && cursors[id]!! in photos[id]!!.indices) assertTrue(idx != cursors[id])
        }
        // Writes point at the advanced photo and never at a live screen.
        for (w in out.writes) {
            assertEquals(photos[w.groupId]!![out.cursors[w.groupId]!!], w.uri)
            assertEquals(w.index, out.cursors[w.groupId])
            if (liveHome) assertEquals(0, w.which and FLAG_SYSTEM)
            if (liveLock) assertEquals(0, w.which and FLAG_LOCK)
            assertTrue(w.which != 0)
        }
        // Every static screen with a feeding group is covered by exactly one write.
        val staticScreens = listOfNotNull(if (homeId in feeding && !liveHome) FLAG_SYSTEM else null, if (lockId in feeding && !liveLock) FLAG_LOCK else null)
        for (flag in staticScreens) assertEquals(1, out.writes.count { it.which and flag != 0 })
    }

    /** A pinned index is shown as-is; an out-of-range pin is ignored; the lock screen shares it when both screens share the group. */
    @Property(tries = 1000)
    fun pinnedIndexWins(
        @ForAll @Size(min = 1, max = 6) photos: List<String>, @ForAll @IntRange(min = -2, max = 8) pin: Int,
        @ForAll @IntRange(min = -1, max = 10) cursor: Int, @ForAll shuffle: Boolean, @ForAll seed: Long,
    ) {
        val out = RotationPlan.advance("g", "g", { photos }, { cursor }, shuffle, liveHome = false, liveLock = false, Random(seed), pinned = mapOf("g" to pin))
        val idx = out.cursors["g"]!!
        if (pin in photos.indices) assertEquals(pin, idx) else assertTrue(idx in photos.indices)
        assertEquals(1, out.writes.size)
        assertEquals(photos[idx], out.writes.single().uri)
        assertEquals(FLAG_SYSTEM or FLAG_LOCK, out.writes.single().which)
        assertEquals(0, out.writes.single().panels)
    }

    /** With a separate cover group, each static screen gets one inner write from its group and one cover write from the cover group. */
    @Property(tries = 1000)
    fun coverGroupSplitsPanels(
        @ForAll @Size(min = 1, max = 5) homePhotos: List<String>, @ForAll @Size(max = 5) coverPhotos: List<String>,
        @ForAll liveHome: Boolean, @ForAll liveLock: Boolean, @ForAll shuffle: Boolean, @ForAll seed: Long,
    ) {
        val photos = mapOf("h" to homePhotos, "c" to coverPhotos)
        val out = RotationPlan.advance("h", "h", { photos[it]!! }, { -1 }, shuffle, liveHome, liveLock, Random(seed), coverGroupId = "c")
        val staticWhich = (if (liveHome) 0 else FLAG_SYSTEM) or (if (liveLock) 0 else FLAG_LOCK)
        if (staticWhich == 0 || coverPhotos.isEmpty()) {
            // Nothing to split: the plain shape holds and the cover group never advances for nothing.
            assertTrue(out.writes.all { it.panels == 0 })
            if (coverPhotos.isEmpty() || staticWhich == 0) assertTrue("c" !in out.cursors || staticWhich != 0)
            return
        }
        assertEquals(setOf("h", "c"), out.cursors.keys)
        assertEquals(2, out.writes.size)
        val inner = out.writes.single { it.panels == RotationPlan.PANEL_INNER }
        val cover = out.writes.single { it.panels == RotationPlan.PANEL_COVER }
        assertEquals("h", inner.groupId); assertEquals(homePhotos[out.cursors["h"]!!], inner.uri)
        assertEquals("c", cover.groupId); assertEquals(coverPhotos[out.cursors["c"]!!], cover.uri)
        assertEquals(staticWhich, inner.which); assertEquals(staticWhich, cover.which)
    }

    /** Per-panel live state: each panel gets exactly the flags its engine does not draw, from the same photo. */
    @Property(tries = 1000)
    fun panelsGetTheFlagsTheirEnginesDoNotDraw(
        @ForAll @Size(min = 1, max = 5) photos: List<String>,
        @ForAll innerHome: Boolean, @ForAll innerLock: Boolean, @ForAll coverHome: Boolean, @ForAll coverLock: Boolean,
        @ForAll shuffle: Boolean, @ForAll seed: Long,
    ) {
        val out = RotationPlan.advance(
            "g", "g", { photos }, { -1 }, shuffle, innerHome, innerLock, Random(seed),
            coverLiveHome = coverHome, coverLiveLock = coverLock,
        )
        val idx = out.cursors["g"]!!
        val innerWant = (if (innerHome) 0 else FLAG_SYSTEM) or (if (innerLock) 0 else FLAG_LOCK)
        val coverWant = (if (coverHome) 0 else FLAG_SYSTEM) or (if (coverLock) 0 else FLAG_LOCK)
        for (w in out.writes) assertEquals(photos[idx], w.uri)
        if (innerWant == coverWant) {
            // Same flags on both panels: one write, no panel bits, or none at all.
            assertEquals(if (innerWant == 0) 0 else 1, out.writes.size)
            out.writes.firstOrNull()?.let { assertEquals(innerWant, it.which); assertEquals(0, it.panels) }
        } else {
            assertEquals(innerWant, out.writes.singleOrNull { it.panels == RotationPlan.PANEL_INNER }?.which ?: 0)
            assertEquals(coverWant, out.writes.singleOrNull { it.panels == RotationPlan.PANEL_COVER }?.which ?: 0)
            assertEquals((if (innerWant != 0) 1 else 0) + (if (coverWant != 0) 1 else 0), out.writes.size)
        }
    }
}

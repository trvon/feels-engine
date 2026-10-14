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
}

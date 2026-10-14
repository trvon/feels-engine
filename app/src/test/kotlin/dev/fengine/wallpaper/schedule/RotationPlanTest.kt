package dev.fengine.wallpaper.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RotationPlanTest {
    @Test
    fun sequentialWrapsAround() {
        assertEquals(0, RotationPlan.nextIndex(count = 3, cursor = -1, shuffle = false))
        assertEquals(1, RotationPlan.nextIndex(count = 3, cursor = 0, shuffle = false))
        assertEquals(0, RotationPlan.nextIndex(count = 3, cursor = 2, shuffle = false))
    }

    @Test
    fun emptyAlbumYieldsNothing() {
        assertEquals(-1, RotationPlan.nextIndex(count = 0, cursor = 4, shuffle = true))
    }

    @Test
    fun shuffleNeverRepeatsCurrentWhenThereIsAChoice() {
        val random = Random(42)
        repeat(200) {
            val cursor = it % 5
            val next = RotationPlan.nextIndex(count = 5, cursor = cursor, shuffle = true, random = random)
            assertTrue(next in 0 until 5)
            assertTrue("picked $next again at cursor $cursor", next != cursor)
        }
    }

    @Test
    fun nextTimeOfDayPicksLaterSlotToday() {
        val day = 24L * 60 * 60 * 1000
        val now = 3 * day + 10 * 60 * 60_000L // 10:00 UTC on day 3
        val next = RotationPlan.nextTimeOfDay(now, listOf(7 * 60, 19 * 60), zoneOffsetMillis = 0)
        assertEquals(3 * day + 19 * 60 * 60_000L, next)
    }

    @Test
    fun nextTimeOfDayRollsToTomorrowAfterLastSlot() {
        val day = 24L * 60 * 60 * 1000
        val now = 3 * day + 20 * 60 * 60_000L // 20:00 UTC
        val next = RotationPlan.nextTimeOfDay(now, listOf(7 * 60, 19 * 60), zoneOffsetMillis = 0)
        assertEquals(4 * day + 7 * 60 * 60_000L, next)
    }

    @Test
    fun nextTimeOfDayHonoursZoneOffset() {
        val day = 24L * 60 * 60 * 1000
        val offset = -5L * 60 * 60_000L // UTC-5
        val now = 3 * day + 11 * 60 * 60_000L // 11:00 UTC = 06:00 local
        val next = RotationPlan.nextTimeOfDay(now, listOf(7 * 60), offset)
        assertEquals(3 * day + 12 * 60 * 60_000L, next) // 07:00 local = 12:00 UTC
    }

    @Test
    fun noSlotsMeansNoAlarm() {
        assertNull(RotationPlan.nextTimeOfDay(0, emptyList(), 0))
    }
}

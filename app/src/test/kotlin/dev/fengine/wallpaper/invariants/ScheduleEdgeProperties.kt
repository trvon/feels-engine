package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.schedule.RotationPlan
import java.time.Instant
import java.time.ZoneId
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Review fixes, 2026-10-25: daylight saving, clock jumps, and removing a photo mid-group. */
class ScheduleEdgeProperties {
    private val zones =
        listOf("America/Denver", "Europe/London", "Australia/Sydney", "Asia/Kolkata", "UTC")

    /**
     * The next set time is in the future, within two days, and lands on the slot's wall-clock time,
     * also across a daylight-saving change (except a slot inside the skipped hour, which moves
     * forward by that hour).
     */
    @Property(tries = 2000)
    fun setTimesLandOnTheirWallClockTime(
        @ForAll @LongRange(min = 1_767_225_600_000, max = 1_830_297_600_000) now: Long, // 2026-2028
        @ForAll @IntRange(min = 0, max = 1439) slot: Int,
        @ForAll @IntRange(min = 0, max = 4) z: Int,
    ) {
        val zone = ZoneId.of(zones[z])
        val next = RotationPlan.nextTimeOfDay(now, listOf(slot), zone)!!
        assertTrue(next > now && next - now <= 49 * 3600_000L)
        val local = Instant.ofEpochMilli(next).atZone(zone).toLocalTime()
        val minutes = local.hour * 60 + local.minute
        assertTrue(
            "slot $slot landed at $local in $zone",
            minutes == slot || minutes == (slot + 60) % 1440,
        )
    }

    /** One firing per slot: once a slot ran, the same slot is not due again before the next one. */
    @Property(tries = 1000)
    fun aSetTimeIsDueOncePerOccurrence(
        @ForAll @LongRange(min = 1_767_225_600_000, max = 1_830_297_600_000) now: Long,
        @ForAll @IntRange(min = 0, max = 1439) slot: Int,
        @ForAll @IntRange(min = 0, max = 4) z: Int,
    ) {
        val zone = ZoneId.of(zones[z])
        val fired = RotationPlan.nextTimeOfDay(now, listOf(slot), zone)!!
        val after = RotationPlan.nextTimeOfDay(fired, listOf(slot), zone)!!
        assertTrue(!RotationPlan.due(Mode.TIME_OF_DAY, after - 1, fired, 60, listOf(slot), zone))
        assertTrue(RotationPlan.due(Mode.TIME_OF_DAY, after, fired, 60, listOf(slot), zone))
    }

    /** A last change in the future (a corrected clock) never stalls rotation. */
    @Property(tries = 500)
    fun aFutureLastChangeDoesNotStall(
        @ForAll @LongRange(min = 1_000_000_000_000, max = 2_000_000_000_000) now: Long,
        @ForAll @LongRange(min = 120_000, max = 400_000_000_000) ahead: Long,
        @ForAll @IntRange(min = 15, max = 10080) interval: Int,
    ) {
        assertTrue(RotationPlan.isDue(now, now + ahead, interval))
        assertTrue(
            RotationPlan.nextIntervalAt(now, now + ahead, interval) <= now + interval * 60_000L
        )
    }

    /** Removing a photo keeps the place: the next photo is the one that followed the cursor. */
    @Property(tries = 1000)
    fun removingAPhotoKeepsThePlace(
        @ForAll("sizes") n: Int,
        @ForAll @IntRange(min = -1, max = 30) cursor: Int,
        @ForAll @IntRange(min = 0, max = 30) remove: Int,
    ) {
        val photos = (0 until n).map { "content://media/picker/0/x/media/$it" }
        val c = cursor.coerceAtMost(n - 1)
        val r = remove % n
        val g = Group(id = "g", name = "G", photos = photos, cursor = c)
        val after = g.withoutPhoto(photos[r])
        assertEquals(n - 1, after.rotation.size)
        if (n == 1) return
        // The first photo after the cursor in the original order that is not the removed one.
        val expectedNext = (1..n).map { photos[(c + it).mod(n)] }.first { it != photos[r] }
        val nextIndex = RotationPlan.nextIndex(after.rotation.size, after.cursor, shuffle = false)
        assertEquals(expectedNext, after.rotation[nextIndex])
    }

    @Provide fun sizes(): Arbitrary<Int> = Arbitraries.integers().between(1, 12)
}

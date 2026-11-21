package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.GrantPlan
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Health
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.ui.Format
import java.time.ZoneId
import java.time.ZoneOffset
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Home's health line, the grant upkeep, "Pick it again" and dated captions: the promises the
 * 2026-11-20 review asked for, over arbitrary inputs.
 */
class HealthAndGrantProperties {
    private val utc: ZoneId = ZoneOffset.UTC
    private val hour = 3_600_000L

    private fun uri(id: Int) =
        "content://media/picker/0/com.android.providers.media.photopicker/media/$id"

    @Property(tries = 1000)
    fun theStallLineAppearsExactlyWhenAChangeIsOverdue(
        @ForAll @IntRange(min = 15, max = 1440) interval: Int,
        @ForAll @LongRange(min = 1, max = 30 * 24) hoursSince: Long,
        @ForAll enabled: Boolean,
    ) {
        val now = 2_000_000_000_000L
        val s =
            Settings(
                groups = listOf(Group(id = "g", name = "G", photos = listOf(uri(1)))),
                homeGroupId = "g",
                enabled = enabled,
                mode = Mode.INTERVAL,
                intervalMinutes = interval,
                lastRotationAt = now - hoursSince * hour,
            )
        val line = Health.line(s, now, utc, ago = { "then" })
        val late = enabled && hoursSince * hour > Health.LATE_FACTOR * interval * 60_000L
        assertEquals(late, line?.fix == Health.Fix.APP_SLEEP)
        if (!late) assertNull(line)
    }

    @Property(tries = 500)
    fun unreadablePhotosOnAScreenAreNamedWithTheirGroup(
        @ForAll @IntRange(min = 1, max = 8) n: Int,
        @ForAll @IntRange(min = 0, max = 8) bad: Int,
        @ForAll assigned: Boolean,
    ) {
        val photos = (0 until n).map(::uri)
        val g =
            photos.take(bad.coerceAtMost(n)).fold(Group(id = "g", name = "G", photos = photos)) {
                acc,
                u ->
                acc.markUnreadable(u)
            }
        val s = Settings(groups = listOf(g), homeGroupId = if (assigned) "g" else null)
        val line = Health.line(s, 0, utc, ago = { "" })
        val count = bad.coerceAtMost(n)
        if (assigned && count > 0) {
            assertEquals(Health.Fix.GROUP, line?.fix)
            assertEquals("g", line?.groupId)
            assertTrue(line!!.text.startsWith(if (count == 1) "1 photo" else "$count photos"))
        } else assertNull(line)
    }

    @Provide
    fun groups(): Arbitrary<List<Group>> {
        val ids = Arbitraries.integers().between(0, 60).map(::uri).list().ofMaxSize(10)
        return net.jqwik.api.Combinators.combine(ids, ids, ids, ids)
            .`as` { p, m, pool, skip ->
                Group(
                    name = "G",
                    photos = p,
                    matched = m,
                    pool = pool,
                    skipped = skip,
                    coverUri = p.firstOrNull(),
                )
            }
            .list()
            .ofMaxSize(3)
    }

    @Property(tries = 2000)
    fun releaseNeverTouchesAPhotoAGroupShowsOrAnOrphan(
        @ForAll("groups") groups: List<Group>,
        @ForAll @IntRange(min = 0, max = 80) extra: Int,
        @ForAll @IntRange(min = 4, max = 60) ceiling: Int,
    ) {
        // Held grants: everything the groups name, plus orphans nobody refers to.
        val held =
            (groups.flatMap { it.allUris } + (100 until 100 + extra).map(::uri))
                .distinct()
                .mapIndexed { i, u -> u to i.toLong() }
        val release = GrantPlan.release(held, groups, ceiling)
        val kept =
            groups
                .flatMap {
                    it.photos + it.matched + it.skipped + it.unreadable + listOfNotNull(it.coverUri)
                }
                .map(PhotoId::identity)
                .toSet()
        val pool = groups.flatMap { it.pool }.map(PhotoId::identity).toSet()
        assertTrue("released a shown photo", release.none { PhotoId.identity(it) in kept })
        assertTrue("released an orphan or a stranger", release.all { PhotoId.identity(it) in pool })
        val target = (ceiling * GrantPlan.HIGH_WATER).toInt()
        assertTrue(
            "released more than needed",
            release.size <= (held.size - target).coerceAtLeast(0),
        )
        // Oldest first among what may go.
        val free = GrantPlan.releasable(held.map { it.first }, groups).toSet()
        val order = held.filter { it.first in free }.sortedBy { it.second }.map { it.first }
        assertEquals(order.take(release.size), release)
    }

    @Property(tries = 2000)
    fun pickItAgainKeepsThePlaceAndTheShownPhoto(
        @ForAll @IntRange(min = 1, max = 10) n: Int,
        @ForAll @IntRange(min = 0, max = 30) which: Int,
        @ForAll @IntRange(min = -1, max = 30) cursor: Int,
        @ForAll asCover: Boolean,
    ) {
        val photos = (0 until n).map(::uri)
        val old = photos[which % n]
        val base =
            Group(id = "g", name = "G", photos = photos, coverUri = if (asCover) old else null)
        val g =
            base.markUnreadable(old).let {
                it.copy(cursor = cursor.coerceIn(-1, it.rotation.size - 1))
            }
        val shown = g.rotation.getOrNull(g.cursor)
        val new =
            "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/c$which"
        val r = g.replacePhoto(old, new)
        assertEquals("same place", photos.indexOf(old), r.photos.indexOf(new))
        assertFalse("readable again", r.isUnreadable(new))
        assertTrue("old gone", r.allUris.none { PhotoId.identity(it) == PhotoId.identity(old) })
        if (asCover) assertEquals(new, r.coverUri)
        if (shown != null)
            assertEquals(if (shown == old) new else shown, r.rotation.getOrNull(r.cursor))
        assertTrue(r.cursor in -1 until r.rotation.size)
    }

    @Property(tries = 2000)
    fun addingAnUnreadablePhotoAgainBringsItBack(
        @ForAll @IntRange(min = 1, max = 10) n: Int,
        @ForAll @IntRange(min = 0, max = 30) which: Int,
        @ForAll @IntRange(min = 0, max = 10) bad: Int,
        @ForAll @IntRange(min = -1, max = 30) cursor: Int,
        @ForAll @IntRange(min = 0, max = 3) extra: Int,
    ) {
        val photos = (0 until n).map(::uri)
        val g =
            photos
                .take(bad)
                .fold(Group(id = "g", name = "G", photos = photos)) { acc, u ->
                    acc.markUnreadable(u)
                }
                .let { it.copy(cursor = cursor.coerceIn(-1, it.rotation.size - 1)) }
        val old = photos[which % n]
        // The same photo back under another URI string with the same identity.
        val again =
            old.replace("picker/0/com.android.providers.media.photopicker", "external/images")
        val added = (0 until extra).map { uri(1000 + it) }
        val shown = g.rotation.getOrNull(g.cursor)
        val r = g.withPhotos(listOf(again) + added)
        assertFalse("readable again", r.isUnreadable(again))
        assertEquals("same place", photos.indexOf(old), r.photos.indexOf(again))
        assertEquals("no duplicate", n + extra, r.photos.size)
        assertTrue("new photos appended", r.photos.takeLast(extra) == added)
        if (
            shown != null && PhotoId.identity(shown) != PhotoId.identity(old) && shown in r.rotation
        )
            assertEquals(shown, r.rotation.getOrNull(r.cursor))
        assertTrue(r.cursor in -1 until r.rotation.size)
    }

    @Property(tries = 1000)
    fun aCaptionNeverShowsABareTimeForAnotherDay(
        @ForAll @LongRange(min = 0, max = 10 * 24) hoursAgo: Long,
        @ForAll @IntRange(min = 0, max = 23) nowHour: Int,
    ) {
        val now = 1_800_000_000_000L - (1_800_000_000_000L % (24 * hour)) + nowHour * hour
        val at = now - hoursAgo * hour
        val text = Format.whenAt(at, now, utc)
        val sameDay = at / (24 * hour) == now / (24 * hour)
        assertEquals(sameDay, text.startsWith("at "))
        if (!sameDay) assertTrue(text, text.startsWith("yesterday at ") || text.startsWith("on "))
    }
}

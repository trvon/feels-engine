package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.wallpaper.CopyPlan
import dev.fengine.wallpaper.wallpaper.SharePlan
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Shared photos: the copy is the photo. A file a group uses is never deleted, an unused one goes
 * only after the grace period, it is never copied again, and the owner's choice decides whether it
 * shows or is looked through.
 */
class SharePlanProperties {
    private val authority = "dev.fengine.wallpaper.photos"

    private fun shared(i: Int) =
        SharePlan.uriFor(
            authority,
            SharePlan.nameFor(
                "content://com.google.android.apps.photos.contentprovider/0/1/mediakey%3A$i"
            ),
        )

    @Property(tries = 1000)
    fun aFileAGroupUsesIsNeverDeletedAndUnusedOnesGoAfterTheGrace(
        @ForAll @IntRange(min = 0, max = 12) n: Int,
        @ForAll @IntRange(min = 0, max = 12) used: Int,
        @ForAll @LongRange(min = 0, max = 2 * 3_600_000L) age: Long,
        @ForAll asPool: Boolean,
    ) {
        val uris = (0 until n).map(::shared)
        val inUse = uris.take(used)
        val g =
            if (asPool) Group(name = "G", rule = "beach", pool = inUse)
            else Group(name = "G", photos = inUse)
        val now = 10_000_000_000L
        val files = uris.associate { SharePlan.fileName(it)!! to now - age }
        val gone = SharePlan.unused(files, listOf(g), now)
        val usedNames = inUse.map { SharePlan.fileName(it)!! }.toSet()
        assertTrue("deleted a used file", gone.none { it in usedNames })
        val expected = if (age >= SharePlan.GRACE_MS) files.keys - usedNames else emptySet()
        assertEquals(expected.sorted(), gone)
    }

    @Property(tries = 500)
    fun sharedPhotosAreNeverCopiedAgain(
        @ForAll @IntRange(min = 0, max = 10) n: Int,
        @ForAll keep: Boolean,
    ) {
        val uris = (0 until n).map(::shared)
        val picked = "content://media/picker/0/com.android.providers.media.photopicker/media/7"
        val plan = CopyPlan.plan(uris + picked, emptySet(), keep)
        assertTrue(plan.save.none(SharePlan::isShared))
        assertEquals(if (keep) listOf(picked) else emptyList<String>(), plan.save)
    }

    @Property(tries = 500)
    fun lookingThroughGoesToThePoolOnlyWithADescription(
        @ForAll @IntRange(min = 1, max = 8) n: Int,
        @ForAll lookThrough: Boolean,
        @ForAll described: Boolean,
    ) {
        val uris = (0 until n).map(::shared)
        val g = Group(name = "G", rule = if (described) "my kids" else null)
        val r = SharePlan.apply(g, uris + uris.take(1), lookThrough)
        if (lookThrough && described) {
            assertEquals(uris, r.pool)
            assertTrue(r.photos.isEmpty())
        } else {
            assertEquals(uris, r.photos)
            assertTrue(r.pool.isEmpty())
        }
    }

    @Property(tries = 200)
    fun theSameItemSharedAgainNamesTheSameFile(@ForAll @IntRange(min = 0, max = 99) i: Int) {
        assertEquals(shared(i), shared(i))
        assertTrue(SharePlan.isShared(shared(i)))
        assertFalse(SharePlan.isShared("content://media/external/images/media/$i"))
    }
}

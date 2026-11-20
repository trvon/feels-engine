package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.wallpaper.CopyPlan
import dev.fengine.wallpaper.wallpaper.PhotoCopies
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One copy-worker run, whatever copies exist already: with copies on, afterwards there is exactly
 * one copy per photo and none for anything else, nothing a group still shows is deleted, and a
 * second run has nothing to do; with copies off, everything goes.
 */
class CopyPlanProperties {
    @Provide
    fun photos(): Arbitrary<List<String>> =
        Arbitraries.integers()
            .between(0, 30)
            .map {
                if (it % 3 == 0) "content://media/external/images/media/$it"
                else "content://media/picker/0/com.android.providers.media.photopicker/media/$it"
            }
            .list()
            .ofMaxSize(20)

    @Provide
    fun existing(): Arbitrary<Set<String>> =
        Arbitraries.integers()
            .between(0, 40)
            .map { PhotoCopies.name("content://media/external/images/media/$it") }
            .set()
            .ofMaxSize(20)

    @Property(tries = 2000)
    fun oneRunLeavesExactlyTheRightCopies(
        @ForAll("photos") photos: List<String>,
        @ForAll("existing") existing: Set<String>,
        @ForAll keep: Boolean,
    ) {
        val plan = CopyPlan.plan(photos, existing, keep)
        val after = existing - plan.delete.toSet() + plan.save.map(PhotoCopies::name)
        val wanted = photos.map(PhotoCopies::name).toSet()
        if (keep) {
            assertEquals(wanted, after)
            assertTrue("never deletes a copy still in use", plan.delete.none { it in wanted })
            assertEquals(
                "saves each photo once",
                plan.save.size,
                plan.save.map(PhotoId::identity).toSet().size,
            )
            assertTrue(
                "a second run has nothing to do",
                CopyPlan.plan(photos, after, true).let {
                    it.save.isEmpty() && it.delete.isEmpty()
                },
            )
        } else {
            assertTrue(after.isEmpty())
            assertTrue(plan.save.isEmpty())
        }
    }
}

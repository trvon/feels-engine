package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.FolderSync
import dev.fengine.wallpaper.data.GrantPlan
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Folder sources: syncs never lose order, never bring back removed photos, never empty on failure.
 */
class FolderSyncProperties {
    private val trees =
        listOf("content://ext/tree/primary%3APictures", "content://ext/tree/primary%3ADCIM")

    @Provide
    fun docs(): Arbitrary<List<String>> =
        Arbitraries.of(trees)
            .flatMap { t -> Arbitraries.integers().between(1, 40).map { "$t/document/$it" } }
            .list()
            .ofMaxSize(30)

    @Property(tries = 1000)
    fun mergeKeepsOrderAddsNewAndDropsGone(
        @ForAll("docs") current: List<String>,
        @ForAll("docs") listed: List<String>,
        @ForAll("docs") excluded: List<String>,
    ) {
        val out = FolderSync.merge(current, listed, excluded)
        val listedIds = listed.map(PhotoId::identity).toSet()
        val excludedIds = excluded.map(PhotoId::identity).toSet()
        // Only photos the folder still has, none removed by the person, once each.
        assertTrue(out.all { it in listedIds && it !in excludedIds })
        assertEquals(out.size, out.toSet().size)
        // Photos already in the group keep their relative order and come first.
        val kept = PhotoId.distinct(current).filter { it in listedIds && it !in excludedIds }
        assertEquals(kept, out.take(kept.size))
        // Every eligible listed photo is there.
        assertEquals((listedIds - excludedIds), out.toSet())
    }

    @Property(tries = 500)
    fun mergeIsIdempotentAndBounded(
        @ForAll("docs") current: List<String>,
        @ForAll("docs") listed: List<String>,
    ) {
        val once = FolderSync.merge(current, listed, emptyList(), max = 10)
        assertTrue(once.size <= 10)
        assertEquals(once, FolderSync.merge(once, listed, emptyList(), max = 10))
    }

    @Property(tries = 500)
    fun aFolderThatFailsToListKeepsItsPhotos(@ForAll("docs") current: List<String>) {
        val listed =
            FolderSync.listing(trees, current) { tree ->
                if (tree == trees[0]) null else emptyList()
            }
        val out = FolderSync.merge(current, listed, emptyList())
        val failedFolder = PhotoId.distinct(current).filter { FolderSync.belongsTo(it, trees[0]) }
        assertEquals(failedFolder, out)
    }

    @Property(tries = 300)
    fun stopFollowingRemovesOnlyThatFolder(@ForAll("docs") photos: List<String>) {
        val g = Group(name = "g", folders = trees, folderPhotos = PhotoId.distinct(photos))
        val after = g.withoutFolder(trees[0])
        assertFalse(trees[0] in after.folders)
        assertTrue(after.allPhotos.none { FolderSync.belongsTo(it, trees[0]) })
        assertEquals(
            g.folderPhotos.filter { FolderSync.belongsTo(it, trees[1]) },
            after.folderPhotos,
        )
    }

    @Property(tries = 300)
    fun aRemovedFolderPhotoStaysOutAfterTheNextSync(@ForAll("docs") photos: List<String>) {
        val ps = PhotoId.distinct(photos)
        if (ps.isEmpty()) return
        val g = Group(name = "g", folders = trees, folderPhotos = ps).excluding(ps.first())
        val synced = FolderSync.merge(g.folderPhotos, ps, g.excluded)
        assertFalse(ps.first() in synced)
    }

    @Property(tries = 300)
    fun retakeTakesFolderTreesNotFolderPhotos(@ForAll("docs") photos: List<String>) {
        val g =
            Group(
                name = "g",
                photos = listOf("content://media/picker/0/x/media/1"),
                folders = trees,
                folderPhotos = PhotoId.distinct(photos),
            )
        val retake = GrantPlan.retake(listOf(g))
        assertTrue(retake.containsAll(trees))
        assertTrue(retake.none { it in g.folderPhotos })
        assertTrue("content://media/picker/0/x/media/1" in retake)
    }

    @Property(tries = 300)
    fun foldersSurviveTheJsonRoundTrip(@ForAll("docs") photos: List<String>) {
        val g = Group(name = "g", folders = trees, folderPhotos = photos)
        assertEquals(listOf(g), Group.decode(Group.encode(listOf(g))))
    }

    @Property(tries = 1)
    fun labelsReadLikeFolderNames() {
        assertEquals(
            "Wallpapers",
            FolderSync.label(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2FWallpapers"
            ),
        )
        assertEquals(
            "Phone storage",
            FolderSync.label("content://com.android.externalstorage.documents/tree/primary%3A"),
        )
        assertEquals(
            "DCIM",
            FolderSync.label("content://com.android.externalstorage.documents/tree/primary%3ADCIM"),
        )
    }
}

package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Removing a photo removes it everywhere and nothing else; library finds go, picked photos stay. */
class PhotoRemovalProperties {
    @Provide fun uris(): Arbitrary<List<String>> = Arbitraries.of(
        "content://media/external/images/media/7", "content://media/external/images/media/8",
        "content://media/picker/0/com.android.providers.media.photopicker/media/7",
        "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/9", "file:///data/x.jpg",
    ).list().ofMaxSize(5)

    @Provide fun one(): Arbitrary<String> = Arbitraries.of("content://media/external/images/media/7", "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/9", "file:///data/x.jpg")

    @Property(tries = 2000)
    fun withoutPhotoRemovesEveryCopyAndNothingElse(
        @ForAll("uris") p: List<String>, @ForAll("uris") m: List<String>, @ForAll("uris") pool: List<String>, @ForAll("uris") sk: List<String>, @ForAll("one") gone: String,
    ) {
        val g = Group(name = "g", photos = p, matched = m, pool = pool, skipped = sk, coverUri = gone)
        val out = g.withoutPhoto(gone)
        val id = PhotoId.identity(gone)
        for (list in listOf(out.photos, out.matched, out.pool, out.skipped)) assertTrue(list.none { PhotoId.identity(it) == id })
        assertTrue(out.allPhotos.none { PhotoId.identity(it) == id })
        assertEquals(null, out.coverUri)
        assertEquals(p.filterNot { PhotoId.identity(it) == id }, out.photos)
        assertEquals(m.filterNot { PhotoId.identity(it) == id }, out.matched)
    }

    @Property(tries = 2000)
    fun libraryFindsGoPickedPhotosStay(@ForAll("uris") p: List<String>, @ForAll("uris") m: List<String>, @ForAll("uris") pool: List<String>) {
        val g = Group(name = "g", photos = p, matched = m, pool = pool)
        val out = g.withoutLibraryFinds()
        assertTrue("a library find survived", (out.matched + out.pool).none(Group::isLibraryUri))
        // Picked photos are never touched, even when a library find was the same photo.
        assertEquals(p, out.photos)
        assertEquals(m.filterNot(Group::isLibraryUri), out.matched)
        assertEquals(out, out.withoutLibraryFinds())
    }
}

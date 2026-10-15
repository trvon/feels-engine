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

/**
 * One kind of group: picking and describing both feed the same photos. What a group shows depends
 * only on what was picked, what was found and what was removed, never on whether it has a
 * description; a description is "on" exactly when it has text.
 */
class UnifiedGroupProperties {

    @Provide
    fun uris(): Arbitrary<List<String>> =
        Arbitraries.of("content://media/external/images/media/1", "content://media/external/images/media/2",
            "content://media/picker/0/com.android.providers.media.photopicker/media/2",
            "content://media/picker/0/com.android.providers.media.photopicker/media/3", "content://x/4")
            .list().ofMaxSize(6)

    @Provide
    fun rules(): Arbitrary<String?> = Arbitraries.of("", " ", "my dog", "sunsets at the beach").injectNull(0.3)

    @Property(tries = 2000)
    fun shownPhotosIgnoreTheDescription(
        @ForAll("uris") picked: List<String>, @ForAll("uris") found: List<String>, @ForAll("uris") removed: List<String>,
        @ForAll("uris") pool: List<String>, @ForAll("rules") a: String?, @ForAll("rules") b: String?,
    ) {
        val g = Group(name = "g", photos = picked, matched = found, excluded = removed, pool = pool, rule = a)
        assertEquals(g.allPhotos, g.copy(rule = b).allPhotos)
        // Pool entries are search scope only: they never show unless the description found them.
        assertEquals(g.allPhotos, g.copy(pool = emptyList()).allPhotos)
    }

    @Property(tries = 2000)
    fun everyPickedPhotoShowsUnlessRemoved(
        @ForAll("uris") picked: List<String>, @ForAll("uris") found: List<String>, @ForAll("uris") removed: List<String>, @ForAll("rules") rule: String?,
    ) {
        val g = Group(name = "g", photos = picked, matched = found, excluded = removed, rule = rule)
        val shown = g.allPhotos.map(PhotoId::identity).toSet()
        val gone = removed.map(PhotoId::identity).toSet()
        for (p in picked + found) {
            val id = PhotoId.identity(p)
            assertEquals("$p shown=$shown", id !in gone, id in shown)
        }
        assertEquals(shown.size, g.allPhotos.size) // one tile per photo
    }

    @Property(tries = 500)
    fun descriptionIsOnExactlyWhenItHasText(@ForAll("rules") rule: String?) {
        assertEquals(!rule.isNullOrBlank(), Group(name = "g", rule = rule).hasDescription)
        assertTrue(Group(name = "g").let { !it.hasDescription && it.allPhotos.isEmpty() })
    }
}

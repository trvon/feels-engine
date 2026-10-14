package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import net.jqwik.api.constraints.IntRange
import dev.fengine.wallpaper.smart.KeywordMatcher
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Round-trip and never-crash properties for persistence and the text matcher. */
class GroupCodecProperties {

    @Provide
    fun groups(): Arbitrary<Group> {
        val str = Arbitraries.strings().withCharRange(' ', '￿').ofMaxLength(40)
        val list = str.list().ofMaxSize(6)
        return Combinators.combine(
            str, list, Arbitraries.integers().between(-1, 50), str.injectNull(0.4), list, list, list, str,
        ).`as` { name: String, photos: List<String>, cursor: Int, rule: String?, matched: List<String>, excluded: List<String>, pool: List<String>, note: String ->
            Group(name = name, photos = photos, cursor = cursor, rule = rule, matched = matched,
                excluded = excluded, pool = pool, scanNote = note, coverUri = (photos + matched).randomOrNull())
        }
    }

    @Provide
    fun groupLists(): Arbitrary<List<Group>> = groups().list().ofMaxSize(5)

    @Property(tries = 1000)
    fun groupsSurviveJsonRoundTrip(@ForAll("groupLists") groups: List<Group>) {
        assertEquals(groups, Group.decode(Group.encode(groups)))
    }

    @Property(tries = 1000)
    fun decodeNeverThrowsOnGarbage(@ForAll junk: String) {
        val out = Group.decode(junk)
        assertTrue(out.isEmpty() || out.all { it.id.isNotEmpty() })
    }

    @Property(tries = 1000)
    fun keywordMatcherNeverThrowsAndOnlyReturnsKnownLabels(
        @ForAll description: String,
        @ForAll @Size(max = 30) labels: List<String>,
    ) {
        val out = KeywordMatcher.select(description, labels)
        assertTrue(out.all { it in labels })
    }

    @Property(tries = 500)
    fun allPhotosNeverContainsExcludedOrDuplicates(@ForAll("groups") g: Group) {
        val all = g.allPhotos
        assertEquals(all.size, all.map(PhotoId::identity).toSet().size)
        val gone = g.excluded.map(PhotoId::identity).toSet()
        assertTrue(all.none { PhotoId.identity(it) in gone })
        g.cover?.let { assertTrue(it in all) }
    }

    @Property(tries = 1000)
    fun photoIdentityUnifiesPickerAndLibraryUrisOnly(@ForAll @IntRange(min = 0, max = 100_000) id: Int, @ForAll other: String) {
        val picker = "content://media/picker/0/com.android.providers.media.photopicker/media/$id"
        val library = "content://media/external/images/media/$id"
        assertEquals(PhotoId.identity(picker), PhotoId.identity(library))
        assertEquals(1, PhotoId.distinct(listOf(picker, library, picker)).size)
        // Anything outside MediaStore stays itself, so unrelated providers never collide.
        val foreign = "content://com.example.docs/document/$id"
        assertEquals(foreign, PhotoId.identity(foreign))
        if (!other.startsWith("content://media/")) assertEquals(other, PhotoId.identity(other))
    }

    @Property(tries = 300)
    fun pickedPhotoIsNotListedTwiceWhenFoundUnderTheLibraryUri(@ForAll @IntRange(min = 0, max = 100_000) id: Int) {
        val g = Group(
            name = "g",
            photos = listOf("content://media/picker/0/com.android.providers.media.photopicker/media/$id"),
            matched = listOf("content://media/external/images/media/$id"),
        )
        assertEquals(1, g.allPhotos.size)
        assertEquals(g.photos.single(), g.allPhotos.single())
    }
}

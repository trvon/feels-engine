package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.data.PhotoRecovery
import dev.fengine.wallpaper.schedule.RotationPlan
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A photo that fails to read is never deleted: it sits out of rotation and comes back when it reads
 * again. Grants no group refers to are found and can be put back.
 */
class UnreadablePhotoProperties {
    @Provide
    fun uris(): Arbitrary<List<String>> =
        Arbitraries.of(
                "content://media/picker/0/com.android.providers.media.photopicker/media/7",
                "content://media/external/images/media/7",
                "content://media/picker/0/com.android.providers.media.photopicker/media/8",
                "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/9",
                "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/10",
                "file:///data/x.jpg",
            )
            .list()
            .ofMaxSize(6)

    @Provide fun sizes(): Arbitrary<Int> = Arbitraries.integers().between(1, 12)

    @Property(tries = 2000)
    fun markingUnreadableKeepsEveryPhoto(
        @ForAll("uris") p: List<String>,
        @ForAll("uris") m: List<String>,
        @ForAll("uris") gone: List<String>,
    ) {
        val g = Group(name = "g", photos = p, matched = m)
        val out = gone.fold(g) { acc, u -> acc.markUnreadable(u) }
        assertEquals(p, out.photos)
        assertEquals(m, out.matched)
        assertEquals(g.allPhotos, out.allPhotos)
        // Out of rotation, unless every photo is out: then the group still shows them all.
        val out2 = out.unreadable.map(PhotoId::identity).toSet()
        val left = g.allPhotos.filterNot { PhotoId.identity(it) in out2 }
        assertEquals(left.ifEmpty { g.allPhotos }, out.rotation)
        assertTrue(out.cursor in -1 until out.rotation.size)
        // Idempotent.
        assertEquals(out, gone.fold(out) { acc, u -> acc.markUnreadable(u) })
    }

    @Property(tries = 1000)
    fun markingUnreadableKeepsThePlace(
        @ForAll("sizes") n: Int,
        @ForAll @IntRange(min = -1, max = 30) cursor: Int,
        @ForAll @IntRange(min = 0, max = 30) bad: Int,
    ) {
        val photos = (0 until n).map { "content://media/picker/0/x/media/$it" }
        val c = cursor.coerceAtMost(n - 1)
        val r = bad % n
        val after =
            Group(id = "g", name = "G", photos = photos, cursor = c).markUnreadable(photos[r])
        assertEquals(photos, after.photos)
        if (n == 1) return
        val expectedNext = (1..n).map { photos[(c + it).mod(n)] }.first { it != photos[r] }
        val next = RotationPlan.nextIndex(after.rotation.size, after.cursor, shuffle = false)
        assertEquals(expectedNext, after.rotation[next])
    }

    @Property(tries = 1000)
    fun readableAgainRestoresRotationAndKeepsTheShownPhoto(
        @ForAll("sizes") n: Int,
        @ForAll @IntRange(min = 0, max = 30) cursor: Int,
        @ForAll @IntRange(min = 0, max = 30) bad: Int,
    ) {
        val photos = (0 until n).map { "content://media/picker/0/x/media/$it" }
        val r = bad % n
        val marked =
            Group(id = "g", name = "G", photos = photos, cursor = cursor % n)
                .markUnreadable(photos[r])
        val shown = marked.rotation.getOrNull(marked.cursor)
        val back = marked.markReadable(listOf(photos[r]))
        assertEquals(photos, back.rotation)
        assertTrue(back.unreadable.isEmpty())
        if (shown != null && n > 1) assertEquals(shown, back.rotation[back.cursor])
    }

    @Property(tries = 500)
    fun unreadableSurvivesTheCodec(
        @ForAll("uris") p: List<String>,
        @ForAll("uris") bad: List<String>,
    ) {
        val g = bad.fold(Group(name = "g", photos = p)) { acc, u -> acc.markUnreadable(u) }
        assertEquals(g, Group.fromJson(JSONObject(g.toJson().toString())))
    }

    @Property(tries = 2000)
    fun orphansAreExactlyTheUnreferencedGrants(
        @ForAll("uris") granted: List<String>,
        @ForAll("uris") p: List<String>,
        @ForAll("uris") pool: List<String>,
        @ForAll("uris") bad: List<String>,
    ) {
        val groups =
            listOf(
                bad.fold(Group(name = "a", photos = p)) { acc, u -> acc.markUnreadable(u) },
                Group(name = "b", pool = pool),
            )
        val used = groups.flatMap { it.allUris }.map(PhotoId::identity).toSet()
        val orphans = PhotoRecovery.orphans(granted, groups)
        assertTrue(orphans.none { PhotoId.identity(it) in used })
        assertEquals(
            granted.map(PhotoId::identity).filterNot { it in used }.toSet(),
            orphans.map(PhotoId::identity).toSet(),
        )
        // Putting them back leaves none.
        val put = groups.mapIndexed { i, g ->
            if (i == 0) g.copy(photos = g.photos + orphans) else g
        }
        assertTrue(PhotoRecovery.orphans(granted, put).isEmpty())
    }
}

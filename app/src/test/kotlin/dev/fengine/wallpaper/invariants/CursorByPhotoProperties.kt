package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A rotation saves its cursor after the wallpaper write. An edit to the group in between (photos
 * added at the front, one removed) must not change which photo the saved cursor names.
 */
class CursorByPhotoProperties {
    private fun uri(i: Int) = "content://media/picker/0/x/media/$i"

    @Property(tries = 2000)
    fun cursorNamesTheShownPhotoAfterAnEdit(
        @ForAll @IntRange(min = 1, max = 12) n: Int,
        @ForAll @IntRange(min = 0, max = 30) shown: Int,
        @ForAll @IntRange(min = 0, max = 4) added: Int,
        @ForAll @IntRange(min = -1, max = 30) removed: Int,
    ) {
        val photos = (0 until n).map(::uri)
        val before = Group(id = "g", name = "G", photos = photos)
        val i = shown % n
        val shownUri = before.rotation[i]
        var edited = before.copy(photos = (100 until 100 + added).map(::uri) + photos)
        val gone = if (removed in 0 until n && removed != i) photos[removed] else null
        if (gone != null) edited = edited.withoutPhoto(gone)

        val saved = edited.showing(shownUri, i)
        assertEquals(shownUri, saved.rotation[saved.cursor])
    }

    @Property(tries = 1000)
    fun aRemovedPhotoFallsBackToAValidIndex(
        @ForAll @IntRange(min = 2, max = 12) n: Int,
        @ForAll @IntRange(min = 0, max = 30) shown: Int,
    ) {
        val photos = (0 until n).map(::uri)
        val i = shown % n
        val edited = Group(id = "g", name = "G", photos = photos).withoutPhoto(photos[i])
        val saved = edited.showing(photos[i], i)
        assertTrue(saved.cursor in -1 until saved.rotation.size)
    }
}

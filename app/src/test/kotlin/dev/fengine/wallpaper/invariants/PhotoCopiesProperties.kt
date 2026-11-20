package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.ui.Format
import dev.fengine.wallpaper.wallpaper.PhotoCopies
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * A saved copy belongs to a photo, not to one of its URIs: the picker and library URIs of a photo
 * find the same copy, different photos never share one, and the name is a safe file name.
 */
class PhotoCopiesProperties {
    private fun picker(id: Int) =
        "content://media/picker/0/com.android.providers.media.photopicker/media/$id"

    private fun library(id: Int) = "content://media/external/images/media/$id"

    @Property(tries = 1000)
    fun onePhotoOneCopy(@ForAll @IntRange(min = 0, max = 10_000_000) id: Int) {
        assertEquals(PhotoCopies.name(picker(id)), PhotoCopies.name(library(id)))
        assertTrue(Regex("^[0-9a-f]{32}\\.jpg$").matches(PhotoCopies.name(picker(id))))
    }

    @Property(tries = 1000)
    fun differentPhotosDifferentCopies(
        @ForAll @IntRange(min = 0, max = 10_000_000) a: Int,
        @ForAll @IntRange(min = 0, max = 10_000_000) b: Int,
    ) {
        if (a == b) return
        assertNotEquals(PhotoCopies.name(picker(a)), PhotoCopies.name(picker(b)))
    }

    @Property(tries = 500)
    fun cloudItemsAreTheirOwnPhoto(@ForAll @IntRange(min = 0, max = 1_000_000) id: Int) {
        val cloud = "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/c$id"
        assertNotEquals(PhotoCopies.name(cloud), PhotoCopies.name(picker(id)))
    }

    @Property(tries = 1000)
    fun storageReadsInOneUnit(@ForAll @LongRange(min = 0, max = 50_000_000_000) bytes: Long) {
        val s = Format.megabytes(bytes)
        assertTrue(s, Regex("^\\d+ (KB|MB)$|^\\d+\\.\\d GB$").matches(s))
    }
}

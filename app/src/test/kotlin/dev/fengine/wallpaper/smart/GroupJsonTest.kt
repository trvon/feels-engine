package dev.fengine.wallpaper.smart

import dev.fengine.wallpaper.data.Group
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupJsonTest {
    @Test
    fun roundTripsRuleAndMatches() {
        val g = Group(name = "Beach", photos = listOf("content://a"), rule = "beach days",
            matched = listOf("content://m1", "content://m2"), excluded = listOf("content://m2"), scanNote = "ok")
        val back = Group.decode(Group.encode(listOf(g))).single()
        assertEquals(g, back)
        assertEquals(listOf("content://a", "content://m1"), back.allPhotos)
    }

    @Test
    fun pickedOnlyGroupHasNoRule() {
        val back = Group.decode(Group.encode(listOf(Group(name = "Mine")))).single()
        assertEquals(null, back.rule)
        assertEquals(false, back.hasDescription)
    }
}

class GroupCoverTest {
    @Test
    fun coverFallsBackWhenChosenPhotoLeaves() {
        val g = Group(name = "x", photos = listOf("a", "b"), coverUri = "b")
        assertEquals("b", g.cover)
        assertEquals("a", g.copy(photos = listOf("a")).cover)
        assertEquals("b", Group.decode(Group.encode(listOf(g))).single().coverUri)
    }
}

package dev.fengine.wallpaper.ui

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's headline says what is missing, not "choose a group" when one is already chosen. */
class StatusLineTest {
    @Test
    fun anAssignedGroupWithoutPhotosIsNamed() {
        val g = Group(id = "g", name = "Family")
        val s = Settings(groups = listOf(g), homeGroupId = "g", lockGroupId = "g")
        assertEquals("Add photos to Family to start.", Format.status(s))
        val d = s.copy(groups = listOf(g.copy(rule = "my wife and kids")))
        assertEquals("Family is waiting for photos to look through.", Format.status(d))
    }

    @Test
    fun noScreenAssignedStillAsksForOne() {
        val g = Group(id = "g", name = "Family", photos = listOf("content://x/1"))
        assertEquals(
            "Choose a group for your screens.",
            Format.status(Settings(groups = listOf(g))),
        )
    }
}

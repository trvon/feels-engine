package dev.fengine.wallpaper.smart

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WantedTest {
    @Test
    fun theOwnersPeopleBecomeWhatAPhotoShows() {
        assertEquals(listOf("a woman", "children"), Wanted.parts("my wife and kids"))
        assertEquals(listOf("a woman", "children"), Wanted.parts("wife and kids"))
        assertEquals(listOf("children playing"), Wanted.parts("the kids playing"))
        assertEquals(listOf("a boy", "a girl"), Wanted.parts("Our son & daughter"))
    }

    @Test
    fun otherDescriptionsSplitOnlyOnListWords() {
        assertEquals(listOf("dog at the beach"), Wanted.parts("my dog at the beach"))
        assertEquals(
            listOf("dog at the beach", "sunsets", "children playing"),
            Wanted.parts("my dog at the beach, sunsets, the kids playing"),
        )
        assertEquals(listOf("sunsets with mountains"), Wanted.parts("sunsets with mountains"))
        // "and" inside a word is not a separator.
        assertEquals(listOf("sand dunes"), Wanted.parts("sand dunes"))
    }

    @Property(tries = 2000)
    fun alwaysSomethingToAskAndNeverTooMuch(@ForAll description: String) {
        val parts = Wanted.parts(description)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.size <= Wanted.MAX_PARTS)
        assertEquals(parts, parts.distinct())
    }
}

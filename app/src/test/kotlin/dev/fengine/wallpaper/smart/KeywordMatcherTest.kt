package dev.fengine.wallpaper.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeywordMatcherTest {
    private val labels = listOf("Dog", "Beach", "Cat", "Sunset", "Child", "Food", "Skyscraper", "Mountain", "Snow")

    @Test
    fun matchesWordsAndSynonyms() {
        assertEquals(setOf("Dog", "Beach"), KeywordMatcher.select("my dog at the beach", labels))
        assertEquals(setOf("Child"), KeywordMatcher.select("the kids playing", labels))
        assertEquals(setOf("Skyscraper"), KeywordMatcher.select("city skyline", labels).intersect(setOf("Skyscraper")))
    }

    /** People words against the real bundled vocabulary: "family" must reach faces, not nothing. */
    @Test
    fun peopleWordsReachTheBundledVocabulary() {
        val asset = listOf("src/main/assets/mlkit_labels.txt", "app/src/main/assets/mlkit_labels.txt")
            .map(::File).first { it.exists() }.readLines().filter { it.isNotBlank() }
        for (rule in listOf("family", "my friends", "family photos", "wedding day", "our pets", "beach vacation")) {
            val out = KeywordMatcher.select(rule, asset)
            assertTrue("\"$rule\" matched no label", out.isNotEmpty())
        }
        assertTrue(KeywordMatcher.select("family", asset).containsAll(setOf("Person", "Smile", "Selfie")))
    }

    @Test
    fun pluralsAndNoise() {
        assertEquals(setOf("Mountain"), KeywordMatcher.select("Mountains!", labels))
        assertTrue(KeywordMatcher.select("a", labels).isEmpty())
    }
}

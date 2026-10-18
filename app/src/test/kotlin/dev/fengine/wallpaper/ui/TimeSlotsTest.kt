package dev.fengine.wallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeSlotsTest {
    @Test
    fun savesCompleteSlotsSortedAndDeduplicated() {
        assertEquals(listOf(0, 420, 1140, 1439), TimeSlots.parse("19:00, 7:00, 00:00, 23:59, 07:00"))
    }

    @Test
    fun incompleteOrInvalidDraftNeverSavesAPartialSchedule() {
        listOf(
            "",
            " ",
            "7",
            "7:",
            "7:0",
            "7:00,",
            ",7:00",
            "7:00, 19:",
            "7:00, bad",
            "24:00",
            "23:60",
            "-1:00",
            "7:00:00",
            "999999:00",
            "𝟟:𝟘𝟘", // Supplementary digits must not throw during numeric conversion.
        ).forEach {
            assertNull("Accepted invalid draft: $it", TimeSlots.parse(it))
        }
    }

    @Test
    fun acceptsWhitespaceAndLocalizedDigits() {
        assertEquals(listOf(420, 1140), TimeSlots.parse("  7:00 , 19:00  "))
        assertEquals(listOf(420, 1140), TimeSlots.parse("٧:٠٠, ١٩:٠٠"))
    }
}

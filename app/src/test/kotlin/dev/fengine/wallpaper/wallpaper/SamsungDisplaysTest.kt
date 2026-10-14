package dev.fengine.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungDisplaysTest {
    // android.jar stubs throw at runtime in JVM tests, so use the raw values (FLAG_SYSTEM=1, FLAG_LOCK=2).
    private val both = 1 or 2

    @Test
    fun nonSamsungPassesFlagsThrough() {
        assertEquals(listOf(both), SamsungDisplays.expand(both, samsung = false))
    }

    @Test
    fun samsungWritesInnerThenCover() {
        // 7 = system|lock|phone, 19 = system|lock|sub: the exact values seen in dumpsys wallpaper.
        assertEquals(listOf(7, 19), SamsungDisplays.expand(both, samsung = true))
        assertEquals(listOf(6, 18), SamsungDisplays.expand(2, samsung = true))
    }
}

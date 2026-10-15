package dev.fengine.wallpaper.smart

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Types the app closes with Kotlin's `use { }` must be java.io.Closeable on every supported
 * Android version. SQLiteOpenHelper only became AutoCloseable in Android 10 (API 29), so a
 * subclass that relied on that cast crashed on Android 9 (docs/research/api-inventory.md).
 */
class OldAndroidContractsTest {
    @Test
    fun photoIndexIsCloseableOnAndroid9() {
        assertTrue(java.io.Closeable::class.java.isAssignableFrom(PhotoIndex::class.java))
    }
}

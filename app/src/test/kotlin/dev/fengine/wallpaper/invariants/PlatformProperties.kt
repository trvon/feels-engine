package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Platform
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Version-dependent rules hold on every Android version the app supports, minSdk 28 through 37. */
class PlatformProperties {
    @Provide fun answers(): Arbitrary<Boolean?> = Arbitraries.of(true, false).injectNull(0.4)

    @Property(tries = 3000)
    fun lockScreenRule(
        @ForAll @IntRange(min = 28, max = 37) sdk: Int, @ForAll homeLive: Boolean,
        @ForAll("answers") ours: Boolean?, @ForAll @IntRange(min = -1, max = 5) lockId: Int,
    ) {
        val live = Platform.lockIsLive(sdk, homeLive, ours, lockId)
        // A lock screen with its own still wallpaper is never drawn by the engine without the
        // framework saying so; a static home never makes the lock screen live by itself.
        if (lockId > 0 && (sdk < 34 || ours == null)) assertFalse(live)
        if (!homeLive && (sdk < 34 || ours == null)) assertFalse(live)
        // Below Android 14 the per-screen answer is unavailable and must not be trusted.
        if (sdk < 34) assertEquals(homeLive && lockId <= 0, live)
        // From Android 14 an explicit answer wins.
        if (sdk >= 34 && ours != null) assertEquals(ours, live)
    }

    @Property(tries = 1000)
    fun homeScreenRule(@ForAll @IntRange(min = 28, max = 37) sdk: Int, @ForAll info: Boolean, @ForAll engine: Boolean) {
        val live = Platform.homeIsLive(sdk, info, engine)
        if (info) assertTrue("the framework naming our engine always counts", live)
        // Android 13 hides the answer without QUERY_ALL_PACKAGES: a running engine must count there,
        // or the rotation writes a still photo over it.
        if (sdk < 34 && engine) assertTrue(live)
        // From Android 14 the framework's answer is trusted: a leftover engine never overrides it.
        if (sdk >= 34) assertEquals(info, live)
        if (!info && !engine) assertFalse(live)
    }

    @Property(tries = 200)
    fun grantWarningComesBeforeTheCeiling(@ForAll @IntRange(min = 28, max = 37) sdk: Int) {
        val ceiling = Platform.grantCeiling(sdk)
        assertEquals(if (sdk >= 30) 512 else 128, ceiling)
        assertTrue(Platform.grantWarnAt(sdk) in 1 until ceiling)
        assertTrue("warning leaves room for one full pick of 20", ceiling - Platform.grantWarnAt(sdk) >= 20)
    }

    @Property(tries = 200)
    fun queriesOnlyColumnsThatExist(@ForAll @IntRange(min = 28, max = 37) sdk: Int) {
        // RELATIVE_PATH is API 29 (api-versions.xml); naming it on Android 9 fails the whole query.
        assertEquals(if (sdk >= 29) "relative_path" else "_data", Platform.folderColumn(sdk))
    }

    @Property(tries = 500)
    fun screenshotFolderOnEitherPathShape(@ForAll("folders") folder: String) {
        val relative = "Pictures/$folder/"
        val absolute = "/storage/emulated/0/Pictures/$folder/IMG_1.jpg"
        val expected = folder.equals("Screenshots", ignoreCase = true)
        assertEquals(expected, Platform.isScreenshot(relative))
        assertEquals(expected, Platform.isScreenshot(absolute))
    }

    @Provide fun folders(): Arbitrary<String> = Arbitraries.of("Screenshots", "screenshots", "Camera", "Screenshots of the kids", "Family")
}

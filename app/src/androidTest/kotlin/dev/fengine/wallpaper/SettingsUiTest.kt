package dev.fengine.wallpaper

import android.content.res.Configuration
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException

/**
 * UI regression checks using platform accessibility.
 * Run on a disposable emulator: instrumentation force-stops the target, which can detach live wallpaper.
 */
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test
    fun settingsSurvivesRecreationAndBackReturnsHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            clickText("Settings")
            assertVisible("Rotation, wallpaper and the way the app looks.")
            scenario.recreate()
            assertVisible("Rotation, wallpaper and the way the app looks.")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertVisible("FEELS ENGINE")
            clickText("Settings")
            clickText("Back")
            assertVisible("FEELS ENGINE")
        }
    }

    @Test
    fun statusBarIconsFollowSystemLightAndDarkMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                assertEquals(
                    "Dark status-bar icons must be used over the light app background",
                    !dark,
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars,
                )
            }
        }
    }

    private fun assertVisible(text: String) {
        awaitText(text)
    }

    // Await asynchronous composition/accessibility updates, not just main-thread idleness.
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try {
                automation.waitForIdle(100, 1_000)
            } catch (_: TimeoutException) {
                // Check during animation too.
            }
            findText(text)?.let { return it }
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing text: $text; active window: ${automation.rootInActiveWindow}")
    }

    private fun clickText(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = node.parent ?: error("No clickable ancestor for: $text")
        assertTrue("Click failed: $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findText(text: String): AccessibilityNodeInfo? = findText(automation.rootInActiveWindow, text)

    // Traverse virtual Compose nodes, as uiautomator does; provider text-search is not universal.
    private fun findText(
        node: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), text)?.let { return it }
        }
        return null
    }
}

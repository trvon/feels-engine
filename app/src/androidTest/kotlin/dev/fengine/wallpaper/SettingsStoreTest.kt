package dev.fengine.wallpaper

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** DataStore on a real Android: what goes in comes out, through the file. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun settingsSurviveTheFile() = runBlocking {
        val store = SettingsStore(context)
        val g = Group(name = "Weekend", photos = listOf("content://media/external/images/media/1"), rule = null)
        store.update { Settings(groups = listOf(g), homeGroupId = g.id, lockGroupId = g.id, mode = Mode.TIME_OF_DAY, timesOfDay = listOf(420, 1140), shuffle = true, enabled = true, dreamGroupId = g.id) }
        val back = SettingsStore(context).read()
        assertEquals(listOf(g), back.groups)
        assertEquals(g.id, back.homeGroupId); assertEquals(g.id, back.lockGroupId); assertEquals(g.id, back.dreamGroupId)
        assertEquals(Mode.TIME_OF_DAY, back.mode); assertEquals(listOf(420, 1140), back.timesOfDay)
        assertEquals(true, back.shuffle); assertEquals(true, back.enabled)
        // Deleting the group drops every assignment that pointed at it.
        store.update { it.copy(groups = emptyList()) }
        val gone = store.read()
        assertNull(gone.homeGroupId); assertNull(gone.lockGroupId); assertNull(gone.dreamGroupId)
    }
}

package dev.fengine.wallpaper.invariants

import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.SettingsCodec
import dev.fengine.wallpaper.schedule.RotationPlan
import dev.fengine.wallpaper.wallpaper.live.LiveEngineState
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Data and rendering seams only; Android scheduling and process recreation need emulator evidence.
 */
class LifecycleFlowProperties {
    private fun restore(settings: Settings): Settings =
        SettingsCodec.read(mutablePreferencesOf().also { SettingsCodec.write(it, settings) })

    @Property(tries = 1000)
    fun hiddenRotationSequenceDecodesOnlyTheLatestPhotoOnReturn(
        @ForAll @IntRange(min = 2, max = 12) photoCount: Int,
        @ForAll @IntRange(min = 0, max = 40) rotations: Int,
    ) {
        val photos = (0 until photoCount).map { "content://picked/lifecycle/$it" }
        val group = Group(id = "flow", name = "Test", photos = photos, cursor = 0)
        var settings =
            Settings(
                groups = listOf(group),
                homeGroupId = group.id,
                lockGroupId = group.id,
                enabled = true,
            )
        val engine = LiveEngineState()
        engine.onSurface(1080, 2400)
        engine.onPhoto(photos[0])
        val initial = engine.onVisibility(true)
        assertEquals(photos[0], initial.decode)
        assertTrue(engine.onDecoded(photos[0], initial.keepOld).draw)
        engine.onVisibility(false)

        repeat(rotations) {
            val plan =
                RotationPlan.advance(
                    settings.homeGroupId,
                    settings.lockGroupId,
                    photos = { id -> settings.group(id)!!.rotation },
                    cursor = { id -> settings.group(id)!!.cursor },
                    shuffle = false,
                    liveHome = true,
                    liveLock = true,
                )
            assertTrue(plan.writes.isEmpty())
            settings =
                restore(
                    settings.copy(
                        groups =
                            settings.groups.map { g ->
                                g.copy(cursor = plan.cursors.getValue(g.id))
                            }
                    )
                )
            val current = settings.homeGroup!!
            val change = engine.onPhoto(current.rotation[current.cursor])
            assertNull(change.decode)
            assertFalse(change.draw)
            assertFalse(engine.visible)
        }
        val expectedCursor = rotations % photoCount
        assertEquals(expectedCursor, settings.homeGroup!!.cursor)
        val wake = engine.onVisibility(true)
        if (expectedCursor == 0) {
            // A whole cycle returned to the already decoded photo: no work is owed.
            assertEquals(LiveEngineState.Decision.NONE, wake)
        } else {
            assertEquals(photos[expectedCursor], wake.decode)
            assertTrue(engine.onDecoded(photos[expectedCursor], wake.keepOld).startFade)
        }
        assertEquals(LiveEngineState.Decision.NONE, engine.onVisibility(true))
    }

    @Property(tries = 500)
    fun rebuildingAnEngineFromPreferencesDoesNotResetOrAdvanceItsGroup(
        @ForAll mode: Mode,
        @ForAll @IntRange(min = 1, max = 12) photoCount: Int,
        @ForAll @IntRange(min = 0, max = 100) cursor: Int,
        @ForAll enabled: Boolean,
    ) {
        val photos = (0 until photoCount).map { "content://picked/restart/$it" }
        val group =
            Group(id = "restart", name = "Test", photos = photos, cursor = cursor % photoCount)
        val saved =
            Settings(
                groups = listOf(group),
                homeGroupId = group.id,
                lockGroupId = group.id,
                mode = mode,
                enabled = enabled,
            )
        val settings = restore(saved)
        assertEquals(saved, settings)
        // Enabled controls automatic scheduling, not whether a selected wallpaper can render.
        val engine = LiveEngineState()
        val current = settings.homeGroup!!
        engine.onPhoto(current.rotation[current.cursor])
        engine.onSurface(1080, 2400)
        val first = engine.onVisibility(true)
        assertEquals(photos[group.cursor], first.decode)
        val result = engine.onDecoded(first.decode!!, first.keepOld)
        assertTrue(result.draw)
        assertFalse(result.startFade)
        assertEquals(saved, settings)
    }
}

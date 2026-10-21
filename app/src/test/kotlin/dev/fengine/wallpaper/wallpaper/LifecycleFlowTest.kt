package dev.fengine.wallpaper.wallpaper

import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Platform
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.SettingsCodec
import dev.fengine.wallpaper.schedule.RotationPlan
import dev.fengine.wallpaper.wallpaper.live.LiveEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure transitions across the settings, rotation and rendering seam. These do not simulate Android
 * delivering work, killing a process, or sending callbacks: those observations need an emulator.
 */
class LifecycleFlowTest {
    private val photos = (0..3).map { "content://picked/lifecycle/$it" }
    private val group = Group(id = "lifecycle", name = "Test photos", photos = photos, cursor = 0)
    private val initial =
        Settings(
            groups = listOf(group),
            homeGroupId = group.id,
            lockGroupId = group.id,
            enabled = true,
        )

    private fun restored(settings: Settings): Settings =
        SettingsCodec.read(mutablePreferencesOf().also { SettingsCodec.write(it, settings) })

    private fun advance(
        settings: Settings,
        live: Boolean,
    ) =
        RotationPlan.advance(
            settings.homeGroupId,
            settings.lockGroupId,
            photos = { settings.group(it)!!.rotation },
            cursor = { settings.group(it)!!.cursor },
            shuffle = false,
            liveHome = live,
            liveLock = live,
        )

    private fun showing(uri: String): LiveEngineState =
        LiveEngineState().also { engine ->
            engine.onSurface(1080, 2400)
            engine.onPhoto(uri)
            val request = engine.onVisibility(true)
            assertEquals(uri, request.decode)
            assertTrue(engine.onDecoded(uri, request.keepOld).draw)
        }

    @Test
    fun hiddenRotationsPersistCursorsWithoutStaticWritesOrIntermediateDecodes() {
        var settings = initial
        val engine = showing(photos[0])
        engine.onVisibility(false)
        repeat(3) {
            val step = advance(settings, live = true)
            assertTrue(step.writes.isEmpty())
            assertEquals(mapOf(group.id to it + 1), step.cursors)
            settings =
                restored(
                    settings.copy(
                        groups =
                            settings.groups.map { g ->
                                g.copy(cursor = step.cursors.getValue(g.id))
                            }
                    )
                )
            val current = settings.homeGroup!!
            assertEquals(
                LiveEngineState.Decision.NONE,
                engine.onPhoto(current.rotation[current.cursor]),
            )
            assertFalse(engine.visible)
        }
        val wake = engine.onVisibility(true)
        assertEquals(photos[3], wake.decode)
        assertTrue(engine.onDecoded(photos[3], wake.keepOld).startFade)
        assertEquals(3, settings.homeGroup!!.cursor)
        assertEquals(LiveEngineState.Decision.NONE, engine.onVisibility(true))
    }

    @Test
    fun freshEngineReconstructsPersistedPhotoWithoutAdvancingTheCursor() {
        for (mode in Mode.entries) {
            val saved = initial.copy(mode = mode, groups = listOf(group.copy(cursor = 2)))
            val settings = restored(saved)
            assertEquals(saved, settings)
            assertTrue(settings.enabled && settings.canRotate)
            val engine = LiveEngineState()
            val current = settings.homeGroup!!
            assertNull(engine.onPhoto(current.rotation[current.cursor]).decode)
            assertNull(engine.onSurface(1080, 2400).decode)
            val first = engine.onVisibility(true)
            assertEquals(photos[2], first.decode)
            assertTrue(first.keepOld)
            val decoded = engine.onDecoded(photos[2], first.keepOld)
            assertTrue(decoded.draw)
            assertFalse(decoded.startFade)
            assertEquals(2, settings.homeGroup!!.cursor)
        }
    }

    @Test
    fun selectedLiveWallpaperIsNotAStaticTargetEvenBeforeItsEngineStarts() {
        val live = Platform.homeIsLive(sdk = 36, infoIsOurs = true, engineRunning = false)
        assertTrue(live)
        val step = advance(initial, live)
        assertTrue(step.writes.isEmpty())
        assertEquals(mapOf(group.id to 1), step.cursors)
    }

    @Test
    fun replacedWallpaperIsStaticDespiteAnOldRunningEngineSignalOnModernAndroid() {
        val live = Platform.homeIsLive(sdk = 36, infoIsOurs = false, engineRunning = true)
        assertFalse(live)
        val step = advance(initial, live)
        assertEquals(listOf(RotationPlan.Write(group.id, 1, photos[1], 3)), step.writes)
        assertEquals(mapOf(group.id to 1), step.cursors)
    }

    @Test
    fun sameSizeSurfaceRecreationRestoresAFrameWithoutAnotherDecode() {
        val engine = showing(photos[0])
        engine.onSurfaceLost()
        assertFalse(engine.visible)
        assertTrue(engine.dirty)
        assertEquals(LiveEngineState.Decision.NONE, engine.onSurface(1080, 2400))
        val wake = engine.onRedrawNeeded()
        assertTrue(wake.draw)
        assertNull(wake.decode)
        assertEquals(LiveEngineState.Decision.NONE, engine.onVisibility(true))
    }

    @Test
    fun sleepingDuringAFadeOwesACleanFrameInsteadOfResumingTheAnimation() {
        val engine = showing(photos[0])
        val request = engine.onPhoto(photos[1])
        assertTrue(engine.onDecoded(photos[1], request.keepOld).startFade)
        assertTrue(engine.onFadeFrame(0.4f).draw)
        assertTrue(engine.onSurfaceLost().cancelFade)
        assertFalse(engine.fading)
        assertFalse(engine.onFadeFrame(0.8f).draw)
        val wake = engine.onRedrawNeeded()
        assertTrue(wake.draw)
        assertNull(wake.decode)
        assertFalse(wake.startFade)
        assertEquals(photos[1], engine.shown)
        assertFalse(engine.dirty)
    }
}

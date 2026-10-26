package dev.fengine.wallpaper.invariants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.fengine.wallpaper.data.Backdrop
import dev.fengine.wallpaper.data.ColorSource
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.PhotoFit
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.SettingsCodec
import dev.fengine.wallpaper.data.ThemeMode
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The settings file must survive write -> read unchanged, must never throw on a corrupt preference,
 * and must migrate the v0.1 flat photo list into one group for both screens.
 */
class SettingsCodecProperties {

    private val text: Arbitrary<String> =
        Arbitraries.strings().withCharRange(' ', '￿').ofMaxLength(30)

    @Provide
    fun groups(): Arbitrary<List<Group>> =
        Combinators.combine(text, text.list().ofMaxSize(4), text.injectNull(0.5))
            .`as` { name: String, photos: List<String>, rule: String? ->
                Group(name = name, photos = photos, rule = rule)
            }
            .list()
            .ofMaxSize(4)

    /**
     * Settings as the app itself produces them: assignments point at existing groups, slots sorted.
     */
    @Provide
    fun validSettings(): Arbitrary<Settings> =
        groups().flatMap { gs ->
            val pick: Arbitrary<String?> =
                if (gs.isEmpty()) Arbitraries.just(null)
                else Arbitraries.of(gs.map { it.id }).injectNull(0.3)
            Combinators.combine(
                    pick,
                    pick,
                    Arbitraries.of(Mode.entries),
                    Arbitraries.integers().between(1, 24 * 60),
                    Arbitraries.integers().between(0, 24 * 60 - 1).list().ofMaxSize(6),
                    Arbitraries.of(true, false),
                    Arbitraries.of(true, false),
                    text,
                )
                .`as` {
                    home: String?,
                    lock: String?,
                    mode: Mode,
                    interval: Int,
                    times: List<Int>,
                    shuffle: Boolean,
                    enabled: Boolean,
                    last: String ->
                    Settings(
                        gs,
                        home,
                        lock,
                        mode,
                        interval,
                        times.sorted(),
                        shuffle,
                        enabled,
                        last,
                        dreamGroupId = lock ?: home,
                        coverGroupId = home,
                        dreamWirelessOnly = shuffle != enabled,
                        lastRotationAt = interval * 60_000L,
                        themeMode = ThemeMode.entries[interval % 3],
                        colorSource = ColorSource.entries[interval % 2],
                        backdrop = Backdrop.entries[(interval / 2) % 2],
                        photoFit = PhotoFit.entries[(interval / 3) % 2],
                        motionOn = interval % 2 == 0,
                        lastAttemptAt = interval * 70_000L,
                        motionLostAt = interval * 1000L,
                        tapToChange = shuffle,
                        changeOnTheme = enabled,
                        darkAtNight = shuffle != enabled,
                    )
                }
        }

    @Property(tries = 500)
    fun validSettingsRoundTrip(@ForAll("validSettings") s: Settings) {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(prefs, s)
        assertEquals(s, SettingsCodec.read(prefs))
    }

    @Property(tries = 500)
    fun danglingAssignmentsAreDroppedAndSlotsSorted(
        @ForAll("groups") gs: List<Group>,
        @ForAll home: String?,
        @ForAll lock: String?,
        @ForAll times: List<Int>,
    ) {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(
            prefs,
            Settings(groups = gs, homeGroupId = home, lockGroupId = lock, timesOfDay = times),
        )
        val out = SettingsCodec.read(prefs)
        assertEquals(gs, out.groups)
        assertEquals(home?.takeIf { id -> gs.any { it.id == id } }, out.homeGroupId)
        assertEquals(lock?.takeIf { id -> gs.any { it.id == id } }, out.lockGroupId)
        assertEquals(
            home?.takeIf { id -> gs.any { it.id == id } },
            SettingsCodec.read(
                    mutablePreferencesOf().also {
                        SettingsCodec.write(it, Settings(groups = gs, dreamGroupId = home))
                    }
                )
                .dreamGroupId,
        )
        assertEquals(times.sorted(), out.timesOfDay)
        assertTrue(out.timesOfDay.zipWithNext().all { (a, b) -> a <= b })
    }

    @Property(tries = 1000)
    fun readNeverThrowsOnGarbage(
        @ForAll junkGroups: String,
        @ForAll junkMode: String,
        @ForAll junkTimes: String,
    ) {
        val prefs = mutablePreferencesOf()
        prefs[SettingsCodec.Keys.groups] = junkGroups
        prefs[SettingsCodec.Keys.mode] = junkMode
        prefs[SettingsCodec.Keys.timesOfDay] = junkTimes
        prefs[SettingsCodec.Keys.homeGroupId] = junkMode
        val out = SettingsCodec.read(prefs)
        assertTrue(out.mode in Mode.entries)
        assertNull(out.homeGroupId.takeIf { id -> out.groups.none { it.id == id } })
    }

    @Property(tries = 300)
    fun legacyPhotoListMigratesIntoOneGroupOnBothScreens(@ForAll photos: List<String>) {
        val lines = photos.filter { it.isNotBlank() && it.none { c -> c == '\n' || c == '\r' } }
        val prefs = mutablePreferencesOf()
        prefs[SettingsCodec.Keys.legacyPhotos] = lines.joinToString("\n")
        val out = SettingsCodec.read(prefs)
        if (lines.isEmpty()) {
            assertTrue(out.groups.isEmpty())
            return
        }
        assertEquals(1, out.groups.size)
        assertEquals(lines, out.groups.single().photos)
        assertEquals(out.groups.single().id, out.homeGroupId)
        assertEquals(out.groups.single().id, out.lockGroupId)
        // Writing back retires the legacy key for good.
        SettingsCodec.write(prefs, out)
        assertNull(prefs[stringPreferencesKey("photos")])
    }

    @Property(tries = 300)
    fun legacyListNeverOverridesExistingGroups(
        @ForAll("validSettings") s: Settings,
        @ForAll legacy: String,
    ) {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(prefs, s)
        prefs[SettingsCodec.Keys.legacyPhotos] = legacy
        val out = SettingsCodec.read(prefs)
        if (s.groups.isNotEmpty()) assertEquals(s.groups, out.groups)
    }

    /**
     * New installs default to wireless-or-dock only; installs from before the setting keep playing
     * on any charger.
     */
    @Property(tries = 300)
    fun screenSaverDefaultRespectsExistingInstalls(@ForAll("validSettings") s: Settings) {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(prefs, s)
        prefs.remove(
            booleanPreferencesKey("dream_wireless_only")
        ) // as written before the setting existed
        val back = SettingsCodec.read(prefs)
        assertEquals(s.groups.isEmpty(), back.dreamWirelessOnly)
        assertEquals(
            true,
            SettingsCodec.read(mutablePreferencesOf()).dreamWirelessOnly,
        ) // a fresh install
    }
}

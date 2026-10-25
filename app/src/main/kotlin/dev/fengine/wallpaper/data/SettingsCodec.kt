package dev.fengine.wallpaper.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The mapping between [Settings] and DataStore preferences, free of Android types so the JVM
 * invariant suite can round-trip it. [SettingsStore] owns the file; this owns the shape.
 */
object SettingsCodec {
    object Keys {
        val groups = stringPreferencesKey("groups")
        val homeGroupId = stringPreferencesKey("home_group_id")
        val lockGroupId = stringPreferencesKey("lock_group_id")
        val mode = stringPreferencesKey("mode")
        val intervalMinutes = intPreferencesKey("interval_minutes")
        val timesOfDay = stringPreferencesKey("times_of_day")
        val shuffle = booleanPreferencesKey("shuffle")
        val dreamWirelessOnly = booleanPreferencesKey("dream_wireless_only")
        val themeMode = stringPreferencesKey("theme_mode")
        val colorSource = stringPreferencesKey("color_source")
        val backdrop = stringPreferencesKey("backdrop")
        val photoFit = stringPreferencesKey("photo_fit")
        val enabled = booleanPreferencesKey("enabled")
        val lastResult = stringPreferencesKey("last_result")
        val lastRotationAt = longPreferencesKey("last_rotation_at")
        val motionOn = booleanPreferencesKey("motion_on")
        val motionLostAt = longPreferencesKey("motion_lost_at")
        val tapToChange = booleanPreferencesKey("tap_to_change")
        val changeOnTheme = booleanPreferencesKey("change_on_theme")
        val darkAtNight = booleanPreferencesKey("dark_at_night")
        val dreamGroupId = stringPreferencesKey("dream_group_id")
        val coverGroupId = stringPreferencesKey("cover_group_id")

        /** Pre-groups schema (v0.1): a flat photo list. Migrated into a group on first read. */
        val legacyPhotos = stringPreferencesKey("photos")
    }

    fun write(prefs: MutablePreferences, next: Settings) {
        prefs[Keys.groups] = Group.encode(next.groups)
        next.homeGroupId?.let { prefs[Keys.homeGroupId] = it } ?: prefs.remove(Keys.homeGroupId)
        next.lockGroupId?.let { prefs[Keys.lockGroupId] = it } ?: prefs.remove(Keys.lockGroupId)
        prefs[Keys.mode] = next.mode.name
        prefs[Keys.intervalMinutes] = next.intervalMinutes
        prefs[Keys.timesOfDay] = next.timesOfDay.joinToString("\n")
        prefs[Keys.shuffle] = next.shuffle
        prefs[Keys.dreamWirelessOnly] = next.dreamWirelessOnly
        prefs[Keys.themeMode] = next.themeMode.name
        prefs[Keys.colorSource] = next.colorSource.name
        prefs[Keys.backdrop] = next.backdrop.name
        prefs[Keys.photoFit] = next.photoFit.name
        prefs[Keys.enabled] = next.enabled
        prefs[Keys.lastResult] = next.lastResult
        prefs[Keys.lastRotationAt] = next.lastRotationAt
        prefs[Keys.motionOn] = next.motionOn
        prefs[Keys.motionLostAt] = next.motionLostAt
        prefs[Keys.tapToChange] = next.tapToChange
        prefs[Keys.changeOnTheme] = next.changeOnTheme
        prefs[Keys.darkAtNight] = next.darkAtNight
        next.dreamGroupId?.let { prefs[Keys.dreamGroupId] = it } ?: prefs.remove(Keys.dreamGroupId)
        next.coverGroupId?.let { prefs[Keys.coverGroupId] = it } ?: prefs.remove(Keys.coverGroupId)
        prefs.remove(Keys.legacyPhotos)
    }

    /**
     * Never throws: unknown modes, dangling group ids and unparsable slots fall back to defaults,
     * because a bad preference must not brick the app at startup.
     */
    fun read(prefs: Preferences): Settings {
        val defaults = Settings()
        var groups = prefs[Keys.groups]?.let(Group::decode) ?: emptyList()
        var home = prefs[Keys.homeGroupId]
        var lock = prefs[Keys.lockGroupId]
        val legacy = prefs[Keys.legacyPhotos]?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (groups.isEmpty() && legacy.isNotEmpty()) {
            val g = Group(name = "My photos", photos = legacy)
            groups = listOf(g)
            home = g.id
            lock = g.id
        }
        return Settings(
            groups = groups,
            homeGroupId = home?.takeIf { id -> groups.any { it.id == id } },
            lockGroupId = lock?.takeIf { id -> groups.any { it.id == id } },
            mode =
                prefs[Keys.mode]?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
                    ?: defaults.mode,
            intervalMinutes = prefs[Keys.intervalMinutes] ?: defaults.intervalMinutes,
            timesOfDay =
                prefs[Keys.timesOfDay]?.lines()?.mapNotNull { it.toIntOrNull() }?.sorted()
                    ?: defaults.timesOfDay,
            shuffle = prefs[Keys.shuffle] ?: defaults.shuffle,
            // Never written: a new install gets the default; an install that already had groups
            // played on any charger before this setting existed and keeps doing so.
            themeMode = enumOr(prefs[Keys.themeMode], defaults.themeMode),
            colorSource = enumOr(prefs[Keys.colorSource], defaults.colorSource),
            backdrop = enumOr(prefs[Keys.backdrop], defaults.backdrop),
            photoFit = enumOr(prefs[Keys.photoFit], defaults.photoFit),
            dreamWirelessOnly =
                prefs[Keys.dreamWirelessOnly] ?: (defaults.dreamWirelessOnly && groups.isEmpty()),
            enabled = prefs[Keys.enabled] ?: defaults.enabled,
            lastResult = prefs[Keys.lastResult] ?: defaults.lastResult,
            lastRotationAt = prefs[Keys.lastRotationAt] ?: defaults.lastRotationAt,
            motionOn = prefs[Keys.motionOn] ?: defaults.motionOn,
            motionLostAt = prefs[Keys.motionLostAt] ?: defaults.motionLostAt,
            tapToChange = prefs[Keys.tapToChange] ?: defaults.tapToChange,
            changeOnTheme = prefs[Keys.changeOnTheme] ?: defaults.changeOnTheme,
            darkAtNight = prefs[Keys.darkAtNight] ?: defaults.darkAtNight,
            dreamGroupId = prefs[Keys.dreamGroupId]?.takeIf { id -> groups.any { it.id == id } },
            coverGroupId = prefs[Keys.coverGroupId]?.takeIf { id -> groups.any { it.id == id } },
        )
    }

    /**
     * An unknown or missing name (a newer version's value, a fresh install) falls back to the
     * default.
     */
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

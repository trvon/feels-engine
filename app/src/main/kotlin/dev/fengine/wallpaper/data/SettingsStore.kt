package dev.fengine.wallpaper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Single-process settings persistence. The preference shape lives in [SettingsCodec]. */
class SettingsStore(private val context: Context) {
    val settings: Flow<Settings> = context.dataStore.data.map(SettingsCodec::read)

    suspend fun read(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs -> SettingsCodec.write(prefs, transform(SettingsCodec.read(prefs))) }
    }
}

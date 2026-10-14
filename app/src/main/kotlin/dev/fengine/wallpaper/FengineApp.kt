package dev.fengine.wallpaper

import android.app.Application
import dev.fengine.wallpaper.data.SettingsStore

class FengineApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
}

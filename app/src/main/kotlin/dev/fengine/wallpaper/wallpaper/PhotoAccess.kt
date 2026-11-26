package dev.fengine.wallpaper.wallpaper

import android.content.Context
import androidx.core.net.toUri

/**
 * Whether a photo URI opens right now. Opening a cloud item may fetch it; call off the main thread.
 */
object PhotoAccess {
    fun readable(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri.toUri(), "r")!!.use { true }
    }
        .getOrDefault(false)
}

package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.net.Uri

/**
 * Whether a photo URI opens right now. Opening a cloud item may fetch it; call off the main thread.
 */
object PhotoAccess {
    fun readable(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")!!.use { true }
    }
        .getOrDefault(false)
}

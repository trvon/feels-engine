package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.fengine.wallpaper.data.Group
import java.io.File

/** The files behind [SharePlan]: saved while the share screen is open, served by our provider. */
object SharedPhotos {
    private const val TAG = "SharedPhotos"

    fun authority(context: Context) = "${context.packageName}.photos"

    private fun dir(context: Context) = File(context.filesDir, SharePlan.DIR)

    /**
     * Saves a screen-sized copy of [source] and returns the URI the group keeps, or null when
     * [source] cannot be read now. Opening a cloud item may wait on Google Photos: off the main
     * thread.
     */
    fun save(context: Context, source: Uri): String? {
        val name = SharePlan.nameFor(source.toString())
        val out = File(dir(context), name)
        val uri = SharePlan.uriFor(authority(context), name)
        if (out.isFile) return uri
        return runCatching { PhotoCopies.write(context, source, out) }
            .onFailure { Log.d(TAG, "not readable yet: ${it.javaClass.simpleName}") }
            .map { uri }
            .getOrNull()
    }

    /** Deletes shared files no group uses (see [SharePlan.unused]); returns how many went. */
    fun prune(context: Context, groups: List<Group>): Int {
        val files =
            dir(context)
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".jpg") }
                .associate {
                    it.name to it.lastModified()
                }
        val gone = SharePlan.unused(files, groups, System.currentTimeMillis())
        gone.forEach { File(dir(context), it).delete() }
        return gone.size
    }

    /** Shared files kept and the bytes they take. */
    fun usage(context: Context): Pair<Int, Long> {
        val files = dir(context).listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
        return files.size to files.sumOf { it.length() }
    }
}

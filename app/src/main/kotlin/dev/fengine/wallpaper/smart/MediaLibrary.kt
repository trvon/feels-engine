package dev.fengine.wallpaper.smart

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Read-only view of the device's image library through MediaStore. */
class MediaLibrary(private val context: Context) {

    data class Item(val id: Long, val dateAdded: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    /** True when the app may read images (full, or Android 14 partial selection). */
    fun hasAccess(): Boolean = requiredPermissions().any { granted(it) }

    /** Newest images first, capped. Screenshots are skipped by relative path. */
    fun newest(limit: Int): List<Item> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val out = ArrayList<Item>(limit)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(pathCol).orEmpty()
                if (path.contains("Screenshots", ignoreCase = true)) continue
                out += Item(c.getLong(idCol), c.getLong(dateCol))
            }
        }
        return out
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** MediaStore id from a `content://media/external/images/media/<id>` URI, or -1. */
        fun idOf(uri: String): Long = uri.substringAfterLast('/').toLongOrNull() ?: -1L

        /** Permissions to request, by platform version. Any one granted is enough to scan. */
        fun requiredPermissions(): List<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

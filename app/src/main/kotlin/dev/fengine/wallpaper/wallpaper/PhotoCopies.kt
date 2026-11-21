package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.net.toUri
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.wallpaper.live.LiveFrame
import java.io.File
import java.security.MessageDigest

/**
 * Screen-sized copies of the photos in groups, kept in app storage, so a photo that leaves the
 * phone keeps showing. Google Photos' "Free up space" deletes the local original of a backed-up
 * photo, and the picker grant then names a photo that no longer exists; the app has no network and
 * no photo permission, so it cannot fetch the cloud copy itself.
 *
 * A copy is sized for the largest panel the way the live engine decodes it (parallax and zoom
 * included), so it serves every screen at full quality. Every decode tries the original first and
 * falls back to the copy only when the original cannot be read.
 */
object PhotoCopies {
    private const val TAG = "PhotoCopies"
    private const val DIR = "copies"
    private const val QUALITY = 90

    /** WebP 80 at copy size: 0.6-1.7 MB against 1.5-2.6 MB for JPEG 90 (Fold 8, 2026-11-20). */
    private const val WEBP_QUALITY = 80

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, uri: String): File = File(dir(context), name(uri))

    /** One file per photo (see [PhotoId]): picker and library URIs of a photo share a copy. */
    fun name(uri: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(PhotoId.identity(uri).toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".jpg"
    }

    fun has(context: Context, uri: String): Boolean = file(context, uri).isFile

    /** Reads served from a copy since the last [takeServed], for the ledger. */
    private val served = java.util.concurrent.atomic.AtomicInteger()

    fun takeServed(): Int = served.getAndSet(0)

    /**
     * Decodes [uri] with [listener], or its copy when the original cannot be read. Throws the
     * original's failure when there is no copy, so callers keep telling a lost photo apart.
     */
    fun decode(
        context: Context,
        uri: String,
        listener: ImageDecoder.OnHeaderDecodedListener,
    ): Bitmap =
        try {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri.toUri()),
                listener,
            )
        } catch (e: Exception) {
            val copy = file(context, uri)
            if (!copy.isFile) throw e
            Log.i(TAG, "original unreadable (${e.javaClass.simpleName}); using the saved copy")
            served.incrementAndGet()
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(copy), listener)
        }

    /**
     * Saves a copy of [uri] unless one exists. False when the original cannot be read now (it stays
     * without a copy until a later run).
     */
    fun save(context: Context, uri: String): Boolean {
        val out = file(context, uri)
        if (out.isFile) return true
        return runCatching { write(context, uri.toUri(), out) }
            .onFailure { Log.w(TAG, "no copy yet: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * Writes a screen-sized image of [source] to [out]: JPEG, or WebP with [webp] (shared photos,
     * [SharePlan]: about half the size at the same pixels). Throws when [source] cannot be read.
     */
    fun write(context: Context, source: android.net.Uri, out: File, webp: Boolean = false) {
        val panel = Displays(context).largest
        val (tw, th) = LiveFrame.decodeTarget(panel.width, panel.height)
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) {
                d,
                info,
                _ ->
                val (w, h) = LiveFrame.decodeSize(info.size.width, info.size.height, tw, th)
                d.setTargetSize(w, h)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
        out.parentFile?.mkdirs()
        // Written aside and renamed, so a stopped run never leaves half a JPEG behind.
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            tmp.outputStream().use {
                if (webp) bitmap.compress(webpFormat(), WEBP_QUALITY, it)
                else bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
            }
        } finally {
            bitmap.recycle()
        }
        check(tmp.renameTo(out)) { "rename failed" }
    }

    /** File names of the copies kept now. */
    fun names(context: Context): Set<String> =
        dir(context).listFiles().orEmpty().map { it.name }.filter { it.endsWith(".jpg") }.toSet()

    /** Deletes the copies named [names] (see [CopyPlan]). */
    fun delete(context: Context, names: Collection<String>) {
        names.forEach { File(dir(context), it).delete() }
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Copies kept and the bytes they take. */
    fun usage(context: Context): Pair<Int, Long> {
        val files = dir(context).listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
        return files.size to files.sumOf { it.length() }
    }
}

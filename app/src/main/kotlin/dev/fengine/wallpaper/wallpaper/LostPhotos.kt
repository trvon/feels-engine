package dev.fengine.wallpaper.wallpaper

import android.util.Log
import androidx.core.net.toUri
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import java.io.FileNotFoundException

/**
 * One road for a photo that is gone, whoever finds out: the static applier (Rotation), the live
 * engine or the screen saver. The photo is marked unreadable in every group (kept, out of rotation,
 * badged "Can't be read" with "Pick it again"), and the ledger gets a line. Before this, Motion and
 * the screen saver dropped such photos without a trace (docs/product/2026-11-20-product-review.md,
 * item 1).
 */
object LostPhotos {
    private const val TAG = "LostPhotos"

    /** The read failed because the photo or its grant is gone, not for a passing reason. */
    fun isLost(e: Throwable): Boolean =
        e is SecurityException || e is FileNotFoundException || e.cause?.let(::isLost) == true

    suspend fun report(app: FengineApp, uri: String, source: String, e: Throwable) {
        Log.w(TAG, "$source: photo unreadable (${e.javaClass.simpleName}); set aside")
        app.settings.update { s -> s.copy(groups = s.groups.map { it.markUnreadable(uri) }) }
        runCatching {
            LedgerStore(app)
                .record(
                    Ledger.Event.Rotation(
                        source,
                        0,
                        0,
                        "unreadable",
                        reason = "${e.javaClass.simpleName} ${uri.toUri().authority.orEmpty()}",
                    )
                )
        }
    }
}

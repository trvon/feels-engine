package dev.fengine.wallpaper.wallpaper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.FolderSync
import java.util.concurrent.TimeUnit

/**
 * Lists every group's folders and brings their photos up to date ([FolderSync.merge]): new photos
 * join, deleted ones leave, removed ones stay out. Runs when a folder is added, when the app opens
 * and every few hours while any group has a folder.
 */
class FolderSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = (applicationContext as FengineApp).settings
        val groups = store.read().groups.filter { it.folders.isNotEmpty() }
        if (groups.isEmpty()) return Result.success()
        val listings = HashMap<String, List<String>?>()
        for (tree in groups.flatMap { it.folders }.distinct()) {
            if (isStopped) return Result.success()
            listings[tree] =
                runCatching { list(applicationContext, tree.toUri()) }
                    .onFailure { Log.w(TAG, "folder unreadable: ${it.javaClass.simpleName}") }
                    .getOrNull()
        }
        var added = 0
        store.update { s ->
            s.copy(
                groups =
                    s.groups.map { g ->
                        if (g.folders.isEmpty()) return@map g
                        val listed = FolderSync.listing(g.folders, g.folderPhotos) { listings[it] }
                        val synced = FolderSync.merge(g.folderPhotos, listed, g.excluded)
                        added += synced.count { it !in g.folderPhotos }
                        g.withFolderPhotos(synced)
                    }
            )
        }
        Log.i(
            TAG,
            "folders ${listings.size}, unreadable ${listings.count { it.value == null }}, added $added",
        )
        return Result.success()
    }

    companion object {
        const val TAG = "FolderSyncWorker"
        private const val UNIQUE_NAME = "folder-sync"
        const val PERIODIC_NAME = "folder-sync-periodic"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FolderSyncWorker>().build(),
                )
        }

        /** Every six hours while a group has a folder; cancelled when none does. */
        fun schedule(context: Context, anyFolders: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!anyFolders) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FolderSyncWorker>(6, TimeUnit.HOURS).build(),
            )
        }

        /**
         * Image documents under [tree], newest first, subfolders to [FolderSync.MAX_DEPTH], at most
         * [FolderSync.MAX_PHOTOS]. Throws when the folder cannot be read (grant gone, folder
         * deleted), so the caller keeps what it had.
         */
        fun list(context: Context, tree: Uri): List<String> {
            val resolver = context.contentResolver
            val found = ArrayList<Pair<String, Long>>()
            val queue = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to 0))
            val cols =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
            while (queue.isNotEmpty() && found.size < FolderSync.MAX_PHOTOS) {
                val (doc, depth) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
                val cursor =
                    checkNotNull(resolver.query(children, cols, null, null, null)) {
                        "no listing"
                    }
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val mime = c.getString(1).orEmpty()
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                                if (depth < FolderSync.MAX_DEPTH) queue.addLast(id to depth + 1)
                            mime.startsWith("image/") ->
                                found +=
                                    DocumentsContract.buildDocumentUriUsingTree(tree, id)
                                        .toString() to c.getLong(2)
                        }
                    }
                }
            }
            return found
                .sortedByDescending { it.second }
                .map { it.first }
                .take(FolderSync.MAX_PHOTOS)
        }
    }
}

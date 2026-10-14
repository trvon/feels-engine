package dev.fengine.wallpaper.smart

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fengine.wallpaper.FengineApp
import java.util.concurrent.TimeUnit

/**
 * Labels the whole reachable library, newest first, a budgeted slice per run, and re-enqueues
 * itself until every photo has a row in [PhotoIndex]. Labels are computed once per photo, ever;
 * a photo that is already indexed costs one hash lookup. After each slice a described-group scan
 * runs so matches appear while the index is still filling.
 */
class IndexLibraryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val library = MediaLibrary(applicationContext)
        if (!library.hasAccess()) return Result.success()
        val index = PhotoIndex(applicationContext)
        val labeler = Labeler()
        val started = SystemClock.elapsedRealtime()
        var labeled = 0
        var todo = 0
        try {
            val all = library.newest(MAX_LIBRARY).map { it.uri.toString() }
            val pending = index.unlabeled(all)
            todo = pending.size
            Log.i(TAG, "library ${all.size}, indexed ${all.size - todo}, to do $todo")
            for (chunk in pending.take(SLICE).chunked(BATCH)) {
                val results = chunk.map { uri ->
                    uri to runCatching { labeler.label(Images.decode(applicationContext, Uri.parse(uri), DECODE_EDGE)) }
                        .getOrElse { e -> Log.w(TAG, "label failed for $uri", e); emptyMap() }
                }
                index.transaction { results.forEach { (uri, labels) -> index.putLabels(uri, labels) } }
                labeled += chunk.size
                setProgress(workDataOf(KEY_DONE to (all.size - todo + labeled), KEY_TOTAL to all.size))
                if (SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS) break
            }
        } finally {
            labeler.close(); index.close()
        }
        val remaining = todo - labeled
        Log.i(TAG, "labeled $labeled in ${SystemClock.elapsedRealtime() - started} ms, remaining $remaining")
        val app = applicationContext as FengineApp
        if (app.settings.read().hasDescribedGroups && labeled > 0) SmartGroupWorker.enqueue(applicationContext)
        if (remaining > 0) continueSoon()
        return Result.success(workDataOf(KEY_DONE to labeled, KEY_TOTAL to todo))
    }

    private fun continueSoon() {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<IndexLibraryWorker>().setInitialDelay(5, TimeUnit.SECONDS).build(),
        )
    }

    companion object {
        const val TAG = "IndexLibraryWorker"
        const val UNIQUE_NAME = "index-library"
        const val PERIODIC_NAME = "index-library-periodic"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        /** Photos labeled per run; with the time budget below a run stays well inside WorkManager's limit. */
        const val SLICE = 600
        const val BATCH = 20
        const val TIME_BUDGET_MS = 8 * 60_000L
        const val MAX_LIBRARY = 100_000
        const val DECODE_EDGE = 640

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexLibraryWorker>().build(),
            )
        }
    }
}

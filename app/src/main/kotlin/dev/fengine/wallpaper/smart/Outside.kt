package dev.fengine.wallpaper.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Calls into another app's service (AICore for Gemini Nano, Play services for ML Kit labels) can
 * answer late or never: AICore accepted a model download on a Fold 8 and never started it while its
 * own update waited to activate. Every such call goes through here, so a hang becomes a failure the
 * caller already handles, and the work around it finishes. Free of Android types: property-tested.
 */
object Outside {
    /** The call did not answer within its deadline. */
    class TimedOut(val what: String, val ms: Long) : Exception("$what did not answer within $ms ms")

    /**
     * [block], or a failure when it throws or takes longer than [ms]. Cancellation of the caller is
     * never swallowed: it propagates, so a stopped worker stops.
     */
    suspend fun <T> within(ms: Long, what: String, block: suspend () -> T): Result<T> =
        try {
            Result.success(withTimeout(ms) { block() })
        } catch (e: TimeoutCancellationException) {
            Result.failure(TimedOut(what, ms))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * What a model download reports, in the app's own terms (ML Kit's DownloadStatus maps here).
     */
    sealed interface Download {
        data class Started(val bytesToDownload: Long) : Download

        data class Progress(val bytesDownloaded: Long) : Download

        data object Completed : Download

        data class Failed(val error: Throwable) : Download
    }

    /** What the person sees while a download runs. */
    sealed interface DownloadView {
        data object Preparing : DownloadView

        data class Downloading(val bytes: Long) : DownloadView

        /** Nothing arrived for [STALL] ms after the request: AICore has not started it. */
        data object Stalled : DownloadView

        data object Done : DownloadView

        data class Failed(val error: Throwable) : DownloadView

        /**
         * The flow ended without saying Completed or Failed: never leave "Preparing" or a byte
         * count on screen after the download is over.
         */
        data object Ended : DownloadView
    }

    /**
     * Follows [events] and reports each change to [report]: Preparing first, Stalled once [stallMs]
     * pass with no progress (Started alone is not progress; AICore can start and sit), then
     * Downloading, Done or Failed. An exception from the flow reports Failed; a flow that ends
     * without either reports Ended. Returns when the flow ends; a flow that never ends keeps it
     * waiting, so callers run it in their own coroutine and never in front of other work.
     */
    suspend fun watch(events: Flow<Download>, stallMs: Long, report: (DownloadView) -> Unit) {
        report(DownloadView.Preparing)
        coroutineScope {
            var moved = false
            var finished = false
            val stall = launch {
                delay(stallMs)
                if (!moved) report(DownloadView.Stalled)
            }
            try {
                events
                    .onEach { e ->
                        // The first Completed or Failed is final; anything after it is noise.
                        if (finished) return@onEach
                        if (e !is Download.Started) {
                            moved = true
                            stall.cancel()
                        }
                        when (e) {
                            is Download.Started -> Unit
                            is Download.Progress ->
                                report(DownloadView.Downloading(e.bytesDownloaded))
                            Download.Completed -> {
                                finished = true
                                report(DownloadView.Done)
                            }
                            is Download.Failed -> {
                                finished = true
                                report(DownloadView.Failed(e.error))
                            }
                        }
                    }
                    .catch { e ->
                        if (e is CancellationException) throw e
                        if (finished) return@catch
                        moved = true
                        finished = true
                        stall.cancel()
                        report(DownloadView.Failed(e))
                    }
                    .collect()
                if (!finished) report(DownloadView.Ended)
            } finally {
                stall.cancel()
            }
        }
    }

    /** Deadlines, generous for a slow phone and short enough that a worker finishes. */
    const val STALL = 90_000L
    const val STATUS_MS = 20_000L
    const val LABEL_MS = 20_000L
    const val PROMPT_MS = 60_000L
}

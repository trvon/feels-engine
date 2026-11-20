package dev.fengine.wallpaper.smart

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** ML Kit base image labeling (bundled model, about 430 labels, on device). */
class Labeler(threshold: Float = 0.5f) {
    private val client =
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(threshold).build()
        )

    /** Bounded (see [Outside]): a task that never completes fails instead of stalling a worker. */
    suspend fun label(bitmap: Bitmap): Map<String, Float> =
        Outside.within(Outside.LABEL_MS, "ML Kit labels") {
                suspendCancellableCoroutine { cont ->
                    client
                        .process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { labels ->
                            cont.resume(labels.associate { it.text to it.confidence })
                        }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            }
            .getOrThrow()

    fun close() = client.close()
}

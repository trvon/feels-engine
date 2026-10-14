package dev.fengine.wallpaper.smart

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit base image labeling (bundled model, about 430 labels, on device). */
class Labeler(threshold: Float = 0.5f) {
    private val client = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(threshold).build(),
    )

    suspend fun label(bitmap: Bitmap): Map<String, Float> = suspendCancellableCoroutine { cont ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels -> cont.resume(labels.associate { it.text to it.confidence }) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() = client.close()
}

package com.chocolaterabbit.productphotos.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Removes the background using ML Kit's on-device subject segmentation.
 *
 * The product pixels are never altered here: we only compute an alpha (transparency) mask
 * and apply it. Everything inside the mask keeps its original colour.
 */
class BackgroundRemover {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }

    /**
     * Returns a square ARGB bitmap: product opaque, background transparent.
     * @param sensitivity 0 = tight, 1 = balanced, 2 = generous (keeps more of the product).
     */
    suspend fun cutOut(square: Bitmap, sensitivity: Int = 1): Bitmap {
        val w = square.width
        val h = square.height
        val confidence = segment(square)
        val pixels = IntArray(w * h)
        square.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = MaskRefiner.refine(confidence, pixels, w, h, MaskRefiner.Tuning(sensitivity))
        for (i in pixels.indices) {
            pixels[i] = (alpha[i] shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Per-pixel probability (0..1) that the pixel belongs to the subject. */
    private suspend fun segment(bitmap: Bitmap): FloatArray = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(image)
            .addOnSuccessListener { result ->
                val buffer = result.foregroundConfidenceMask
                if (buffer == null) {
                    cont.resumeWithException(IllegalStateException("No subject was found in the photo."))
                    return@addOnSuccessListener
                }
                buffer.rewind()
                val out = FloatArray(bitmap.width * bitmap.height)
                buffer.get(out)
                cont.resume(out)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    fun close() = segmenter.close()
}

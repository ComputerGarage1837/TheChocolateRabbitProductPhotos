package com.chocolaterabbit.productphotos.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

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

    /** Returns a square ARGB bitmap: product opaque, background transparent. */
    suspend fun cutOut(square: Bitmap): Bitmap {
        val confidence = segment(square)
        val alpha = refineMask(confidence, square.width, square.height)
        return applyAlpha(square, alpha)
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

    /**
     * Turns soft confidence into a clean alpha mask:
     *  1. Hard-ish threshold with a narrow soft band so the product edge is crisp but not jagged.
     *  2. A small blur on the alpha only, to feather the edge by about a pixel.
     */
    private fun refineMask(confidence: FloatArray, w: Int, h: Int): IntArray {
        val low = 0.35f
        val high = 0.70f
        val hard = IntArray(w * h) { i ->
            val c = confidence[i]
            val t = ((c - low) / (high - low)).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t) // smoothstep
            (s * 255f).roundToInt()
        }
        return boxBlur3(hard, w, h)
    }

    private fun boxBlur3(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - 1)
            val y1 = minOf(h - 1, y + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - 1)
                val x1 = minOf(w - 1, x + 1)
                var sum = 0
                var n = 0
                for (yy in y0..y1) for (xx in x0..x1) { sum += src[yy * w + xx]; n++ }
                out[y * w + x] = sum / n
            }
        }
        return out
    }

    private fun applyAlpha(src: Bitmap, alpha: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = (alpha[i] shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun close() = segmenter.close()
}

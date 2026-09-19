package com.chocolaterabbit.productphotos.processing

import android.graphics.Bitmap

/**
 * Removes the background using the bundled ISNet segmentation model.
 *
 * The product pixels are never altered here: we only compute an alpha (transparency) mask
 * and apply it. Everything inside the mask keeps its original colour.
 */
class BackgroundRemover(private val segmenter: () -> OnnxSegmenter?) {

    /**
     * Returns a square ARGB bitmap: product opaque, background transparent.
     * @param sensitivity 0 = tight, 1 = balanced, 2 = generous (keeps more of the product).
     */
    fun cutOut(square: Bitmap, sensitivity: Int = 1): Bitmap {
        val model = segmenter() ?: throw IllegalStateException("The background remover is not loaded yet.")
        val w = square.width
        val h = square.height
        val pixels = IntArray(w * h)
        square.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = cutOutAlpha(model, pixels, w, h, sensitivity)
        for (i in pixels.indices) {
            pixels[i] = (alpha[i] shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    companion object {
        /** The whole mask pipeline on plain arrays (shared with the JVM test harness). */
        fun cutOutAlpha(model: OnnxSegmenter, pixels: IntArray, w: Int, h: Int, sensitivity: Int): IntArray {
            val confidence = SubjectMask.compute(model, pixels, w, h)
            return MaskRefiner.refine(confidence, pixels, w, h, MaskRefiner.Tuning(sensitivity))
        }
    }
}

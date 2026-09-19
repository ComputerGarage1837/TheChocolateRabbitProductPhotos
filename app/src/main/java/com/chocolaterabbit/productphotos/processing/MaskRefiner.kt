package com.chocolaterabbit.productphotos.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the segmentation model's per-pixel confidence into a clean product mask.
 *
 * Why this exists: the model alone tends to be unsure about thin, shiny, transparent or
 * light-coloured parts of a product and so cuts them off. We recover them in three steps:
 *
 *  1. Soft threshold of the model confidence (tuned to keep, not to cut).
 *  2. Colour grow: learn what the background looks like from pixels the model is sure are
 *     background, then, within a band around the model's mask, keep any pixel whose colour is
 *     clearly not background. This brings back edges and parts the model missed.
 *  3. Guided filter: snaps the soft mask to the real edges in the photo so the outline follows
 *     the product instead of the model's blurry estimate.
 *  4. Drop small stray islands (specks of background that slipped through).
 */
object MaskRefiner {

    private const val GROW_PASSES = 4

    /** 0 = tight (cut more), 1 = balanced, 2 = generous (keep more). */
    class Tuning(sensitivity: Int) {
        val mlLow: Float
        val mlHigh: Float
        val colourNear: Float   // distance (in background std units) that counts as background
        val colourFar: Float    // distance that counts as definitely product
        val bandFraction: Float // how far beyond the model mask to look, as a fraction of image side

        init {
            when (sensitivity.coerceIn(0, 2)) {
                0 -> { mlLow = 0.40f; mlHigh = 0.70f; colourNear = 4.0f; colourFar = 7.0f; bandFraction = 0.03f }
                2 -> { mlLow = 0.15f; mlHigh = 0.50f; colourNear = 2.2f; colourFar = 4.5f; bandFraction = 0.08f }
                else -> { mlLow = 0.25f; mlHigh = 0.60f; colourNear = 3.0f; colourFar = 5.5f; bandFraction = 0.05f }
            }
        }
    }

    /** @return alpha 0..255 per pixel. */
    fun refine(confidence: FloatArray, pixels: IntArray, w: Int, h: Int, tuning: Tuning): IntArray {
        val n = w * h

        // 1. Soft threshold of the model output.
        val ml = FloatArray(n) { i -> smoothstep(confidence[i], tuning.mlLow, tuning.mlHigh) }

        // 2. Colour grow, repeated so a long thin part (a stem, a ribbon, a handle) is reclaimed
        //    step by step outward from the parts the model did find.
        val bg = BackgroundModel.learn(confidence, pixels)
        val grown = ml.copyOf()
        if (bg != null) {
            val colourAlpha = FloatArray(n) { i -> smoothstep(bg.distance(pixels[i]), tuning.colourNear, tuning.colourFar) }
            val radius = max(2, (min(w, h) * tuning.bandFraction).toInt())
            repeat(GROW_PASSES) {
                val band = dilate(grown, w, h, threshold = 0.5f, radius = radius)
                var changed = false
                for (i in 0 until n) {
                    if (band[i] && colourAlpha[i] > grown[i]) {
                        grown[i] = colourAlpha[i]
                        changed = true
                    }
                }
                if (!changed) return@repeat
            }
        }

        // 3. Guided filter to snap the edge to the photo.
        val guide = FloatArray(n) { i -> luminance(pixels[i]) / 255f }
        val radius = max(4, min(w, h) / 200)
        val filtered = GuidedFilter.apply(guide, grown, w, h, radius, eps = 0.004f)

        // Slight contrast so the edge is crisp but still anti-aliased.
        val alpha = IntArray(n) { i -> (smoothstep(filtered[i], 0.25f, 0.75f) * 255f + 0.5f).toInt().coerceIn(0, 255) }

        // 4. Remove stray islands.
        return dropSmallIslands(alpha, w, h)
    }

    private fun smoothstep(x: Float, lo: Float, hi: Float): Float {
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Binary dilation of (mask > threshold) by a square of the given radius, via separable passes. */
    private fun dilate(mask: FloatArray, w: Int, h: Int, threshold: Float, radius: Int): BooleanArray {
        val src = BooleanArray(w * h) { mask[it] > threshold }
        val tmp = BooleanArray(w * h)
        // horizontal
        for (y in 0 until h) {
            val row = y * w
            var count = 0
            for (x in 0 until w + radius) {
                if (x < w && src[row + x]) count++
                val leave = x - 2 * radius - 1
                if (leave >= 0 && src[row + leave]) count--
                val cx = x - radius
                if (cx in 0 until w) tmp[row + cx] = count > 0
            }
        }
        val out = BooleanArray(w * h)
        // vertical
        for (x in 0 until w) {
            var count = 0
            for (y in 0 until h + radius) {
                if (y < h && tmp[y * w + x]) count++
                val leave = y - 2 * radius - 1
                if (leave >= 0 && tmp[leave * w + x]) count--
                val cy = y - radius
                if (cy in 0 until h) out[cy * w + x] = count > 0
            }
        }
        return out
    }

    /** Keeps connected regions that are at least 1.5% the size of the largest one. */
    private fun dropSmallIslands(alpha: IntArray, w: Int, h: Int): IntArray {
        val n = w * h
        val label = IntArray(n) // 0 = unlabelled / background
        val sizes = ArrayList<Int>()
        sizes.add(0)
        val stack = IntArray(n)
        var next = 1
        for (start in 0 until n) {
            if (alpha[start] < 128 || label[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = next
            var size = 0
            while (sp > 0) {
                val i = stack[--sp]
                size++
                val x = i % w
                val y = i / w
                if (x > 0) visit(i - 1, alpha, label, next, stack, sp).also { sp = it }
                if (x < w - 1) visit(i + 1, alpha, label, next, stack, sp).also { sp = it }
                if (y > 0) visit(i - w, alpha, label, next, stack, sp).also { sp = it }
                if (y < h - 1) visit(i + w, alpha, label, next, stack, sp).also { sp = it }
            }
            sizes.add(size)
            next++
        }
        if (sizes.size <= 2) return alpha // zero or one region: nothing to drop
        val largest = sizes.maxOrNull() ?: return alpha
        val keep = BooleanArray(sizes.size) { idx -> idx != 0 && sizes[idx] >= largest * 0.015f }
        val out = alpha.copyOf()
        // Clear pixels of dropped regions, plus soft-edge pixels that touch no kept region.
        for (i in 0 until n) {
            val l = label[i]
            if (l != 0 && !keep[l]) out[i] = 0
        }
        for (i in 0 until n) {
            if (out[i] in 1..127 && !nearKept(i, w, h, label, keep)) out[i] = 0
        }
        return out
    }

    private fun visit(j: Int, alpha: IntArray, label: IntArray, l: Int, stack: IntArray, sp: Int): Int {
        if (alpha[j] >= 128 && label[j] == 0) {
            label[j] = l
            stack[sp] = j
            return sp + 1
        }
        return sp
    }

    private fun nearKept(i: Int, w: Int, h: Int, label: IntArray, keep: BooleanArray): Boolean {
        val x = i % w
        val y = i / w
        for (yy in max(0, y - 3)..min(h - 1, y + 3)) {
            for (xx in max(0, x - 3)..min(w - 1, x + 3)) {
                val l = label[yy * w + xx]
                if (l != 0 && keep[l]) return true
            }
        }
        return false
    }

    /** Mean and spread of the background colour, learned from confident-background pixels. */
    private class BackgroundModel(
        private val mean: FloatArray,
        private val invStd: FloatArray,
    ) {
        fun distance(c: Int): Float {
            val r = ((c shr 16) and 0xFF) - mean[0]
            val g = ((c shr 8) and 0xFF) - mean[1]
            val b = (c and 0xFF) - mean[2]
            val dr = r * invStd[0]
            val dg = g * invStd[1]
            val db = b * invStd[2]
            return sqrt(dr * dr + dg * dg + db * db)
        }

        companion object {
            fun learn(confidence: FloatArray, pixels: IntArray): BackgroundModel? {
                var n = 0
                val sum = DoubleArray(3)
                val sq = DoubleArray(3)
                for (i in pixels.indices step 2) {
                    if (confidence[i] > 0.08f) continue
                    val c = pixels[i]
                    val r = ((c shr 16) and 0xFF).toDouble()
                    val g = ((c shr 8) and 0xFF).toDouble()
                    val b = (c and 0xFF).toDouble()
                    sum[0] += r; sum[1] += g; sum[2] += b
                    sq[0] += r * r; sq[1] += g * g; sq[2] += b * b
                    n++
                }
                if (n < 500) return null
                val mean = FloatArray(3)
                val invStd = FloatArray(3)
                for (k in 0..2) {
                    val m = sum[k] / n
                    val v = max(0.0, sq[k] / n - m * m)
                    // Floor the spread so a perfectly flat backdrop doesn't make everything "far".
                    val sd = max(6.0, sqrt(v))
                    mean[k] = m.toFloat()
                    invStd[k] = (1.0 / sd).toFloat()
                }
                // If the "background" is very varied (busy scene), the colour test is unreliable: disable it.
                val avgSd = (1f / invStd[0] + 1f / invStd[1] + 1f / invStd[2]) / 3f
                if (avgSd > 45f) return null
                return BackgroundModel(mean, invStd)
            }
        }
    }
}

/** He et al. guided filter with a single-channel guide, using summed-area tables for the box blurs. */
object GuidedFilter {

    fun apply(guide: FloatArray, src: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, radius)
        val meanP = box(src, w, h, radius)
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val ip = FloatArray(n) { guide[it] * src[it] }
        val corrI = box(ii, w, h, radius)
        val corrIp = box(ip, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIp = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, radius)
        val meanB = box(b, w, h, radius)
        return FloatArray(n) { i -> (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f) }
    }

    /** Box blur with edge clamping via a summed-area table. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        val stride = w + 1
        for (y in 1..h) {
            var rowSum = 0.0
            val srcRow = (y - 1) * w
            for (x in 1..w) {
                rowSum += src[srcRow + x - 1]
                sat[y * stride + x] = sat[(y - 1) * stride + x] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = sat[(y1 + 1) * stride + (x1 + 1)] - sat[y0 * stride + (x1 + 1)] -
                    sat[(y1 + 1) * stride + x0] + sat[y0 * stride + x0]
                val area = (y1 - y0 + 1) * (x1 - x0 + 1)
                out[y * w + x] = (sum / area).toFloat()
            }
        }
        return out
    }
}

package com.chocolaterabbit.productphotos.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the segmentation model's per-pixel confidence into a clean product mask.
 *
 * The model alone is unsure about thin, shiny, transparent, light-coloured or multi-coloured
 * parts of a product and cuts them off. Product photos are taken against a plain backdrop, so
 * we lean on that:
 *
 *  1. Soft threshold of the model confidence (tuned to keep, not to cut).
 *  2. Learn the backdrop: a small set of colour clusters from the picture border and from pixels
 *     the model is sure are background. Shadows (same colour, darker) count as backdrop.
 *  3. Anything that is clearly not backdrop AND is connected to what the model found is product.
 *     This reclaims whole chunks the model missed (a differently coloured lid, a label, a ribbon).
 *  4. Close small gaps and fill enclosed holes in the outline.
 *  5. Guided filter snaps the soft edge to the real edges in the photo.
 *  6. Drop small stray islands.
 */
object MaskRefiner {

    /** 0 = tight (cut more), 1 = balanced, 2 = generous (keep more). */
    class Tuning(sensitivity: Int) {
        val mlLow: Float
        val mlHigh: Float
        val colourNear: Float   // distance (in backdrop std units) that still counts as backdrop
        val colourFar: Float    // distance that counts as definitely product
        val reachFraction: Float // how far from the model's mask reclaimed pixels may extend (fraction of side)
        val closeFraction: Float // gap-closing radius (fraction of side)

        init {
            when (sensitivity.coerceIn(0, 2)) {
                0 -> { mlLow = 0.40f; mlHigh = 0.70f; colourNear = 4.0f; colourFar = 7.0f; reachFraction = 0.10f; closeFraction = 0.006f }
                2 -> { mlLow = 0.12f; mlHigh = 0.45f; colourNear = 2.0f; colourFar = 4.0f; reachFraction = 0.35f; closeFraction = 0.015f }
                else -> { mlLow = 0.22f; mlHigh = 0.55f; colourNear = 2.8f; colourFar = 5.0f; reachFraction = 0.22f; closeFraction = 0.010f }
            }
        }
    }

    /** @return alpha 0..255 per pixel. */
    fun refine(confidence: FloatArray, pixels: IntArray, w: Int, h: Int, tuning: Tuning): IntArray {
        val n = w * h
        val side = min(w, h)

        // 1. Soft threshold of the model output.
        val ml = FloatArray(n) { i -> smoothstep(confidence[i], tuning.mlLow, tuning.mlHigh) }
        val mlHard = BooleanArray(n) { ml[it] > 0.5f }

        // 2 + 3. Backdrop model and reclaim of connected non-backdrop pixels.
        val grown = ml.copyOf()
        val backdrop = Backdrop.learn(confidence, pixels, w, h)
        if (backdrop != null) {
            val colourAlpha = FloatArray(n) { i -> smoothstep(backdrop.distance(pixels[i]), tuning.colourNear, tuning.colourFar) }
            val radius = max(4, (side * tuning.reachFraction).toInt())
            var seed = mlHard
            repeat(2) {
                val reach = dilate(seed, w, h, radius)
                val candidate = BooleanArray(n) { i -> seed[i] || (reach[i] && colourAlpha[i] > 0.5f) }
                seed = componentsTouching(candidate, seed, w, h)
            }
            for (i in 0 until n) {
                if (seed[i] && colourAlpha[i] > grown[i]) grown[i] = colourAlpha[i]
            }
        }

        // 4. Close gaps and fill holes in the outline.
        val hard = BooleanArray(n) { grown[it] > 0.5f }
        val closeRadius = max(1, (side * tuning.closeFraction).toInt())
        val closed = erode(dilate(hard, w, h, closeRadius), w, h, closeRadius)
        val filled = fillHoles(closed, w, h, maxHoleFraction = 0.25f)
        for (i in 0 until n) if (filled[i] && !hard[i]) grown[i] = 1f

        // 5. Guided filter to snap the edge to the photo.
        val guide = FloatArray(n) { i -> luminance(pixels[i]) / 255f }
        val radius = max(4, side / 200)
        val filtered = GuidedFilter.apply(guide, grown, w, h, radius, eps = 0.004f)
        val alpha = IntArray(n) { i -> (smoothstep(filtered[i], 0.25f, 0.75f) * 255f + 0.5f).toInt().coerceIn(0, 255) }

        // 6. Remove stray islands.
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

    // ---- morphology -------------------------------------------------------------------------

    /** Binary dilation by a square of the given radius, via separable running counts. */
    fun dilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
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

    fun erode(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val inv = BooleanArray(src.size) { !src[it] }
        val d = dilate(inv, w, h, radius)
        // Pixels near the image border must not be eroded away just because the border is "outside".
        return BooleanArray(src.size) { i ->
            val x = i % w; val y = i / w
            val nearBorder = x < radius || y < radius || x >= w - radius || y >= h - radius
            if (nearBorder) src[i] else !d[i]
        }
    }

    /** Flood-fills from the image border; enclosed regions smaller than maxHoleFraction of the mask are filled. */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int, maxHoleFraction: Float): BooleanArray {
        val n = w * h
        val reached = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        fun push(i: Int) { if (!mask[i] && !reached[i]) { reached[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        var maskArea = 0
        for (i in 0 until n) if (mask[i]) maskArea++
        val maxHole = (maskArea * maxHoleFraction).toInt()

        // Label each hole and fill the small ones.
        val out = mask.copyOf()
        val label = IntArray(n)
        var next = 1
        for (start in 0 until n) {
            if (mask[start] || reached[start] || label[start] != 0) continue
            sp = 0
            stack[sp++] = start; label[start] = next
            var size = 0
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                size++; members.add(i)
                val x = i % w; val y = i / w
                fun visit(j: Int) { if (!mask[j] && !reached[j] && label[j] == 0) { label[j] = next; stack[sp++] = j } }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (size <= maxHole) for (i in members) out[i] = true
            next++
        }
        return out
    }

    /** Pixels of `candidate` that belong to a connected component containing at least one `seed` pixel. */
    private fun componentsTouching(candidate: BooleanArray, seed: BooleanArray, w: Int, h: Int): BooleanArray {
        val n = w * h
        val out = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        for (i in 0 until n) {
            if (seed[i] && candidate[i] && !out[i]) { out[i] = true; stack[sp++] = i }
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            fun visit(j: Int) { if (candidate[j] && !out[j]) { out[j] = true; stack[sp++] = j } }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        return out
    }

    /** Keeps connected regions that are at least 1.5% the size of the largest one. */
    private fun dropSmallIslands(alpha: IntArray, w: Int, h: Int): IntArray {
        val n = w * h
        val label = IntArray(n)
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
        if (sizes.size <= 2) return alpha
        val largest = sizes.maxOrNull() ?: return alpha
        val keep = BooleanArray(sizes.size) { idx -> idx != 0 && sizes[idx] >= largest * 0.015f }
        val out = alpha.copyOf()
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

    // ---- backdrop colour model ----------------------------------------------------------------

    /**
     * A few colour clusters describing the backdrop. Distance is measured in "std units" and
     * splits into a chroma part (colour cast) and a brightness part; darker-than-backdrop is
     * discounted so shadows stay backdrop, while a different colour or a brighter pixel is product.
     */
    private class Backdrop(private val clusters: List<Cluster>) {

        class Cluster(val mean: FloatArray, val invStd: FloatArray)

        fun distance(c: Int): Float {
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            var best = Float.MAX_VALUE
            for (k in clusters) {
                val dr = (r - k.mean[0]) * k.invStd[0]
                val dg = (g - k.mean[1]) * k.invStd[1]
                val db = (b - k.mean[2]) * k.invStd[2]
                val lum = (dr + dg + db) / 3f
                val cr = dr - lum; val cg = dg - lum; val cb = db - lum
                val chroma = sqrt(cr * cr + cg * cg + cb * cb)
                val bright = if (lum >= 0f) lum else -lum * SHADOW_WEIGHT
                val d = sqrt(chroma * chroma + bright * bright)
                if (d < best) best = d
            }
            return best
        }

        companion object {
            private const val SHADOW_WEIGHT = 0.35f
            private const val K = 3

            fun learn(confidence: FloatArray, pixels: IntArray, w: Int, h: Int): Backdrop? {
                // Sample: the outer border ring plus anything the model is confident is background.
                val ring = max(2, min(w, h) / 16)
                val samples = ArrayList<Int>()
                var i = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val border = x < ring || y < ring || x >= w - ring || y >= h - ring
                        if ((border && confidence[i] < 0.5f) || confidence[i] < 0.05f) {
                            if ((i and 3) == 0) samples.add(pixels[i]) // every 4th pixel is plenty
                        }
                        i++
                    }
                }
                if (samples.size < 400) return null

                // k-means on RGB.
                val means = Array(K) { k -> rgb(samples[(samples.size.toLong() * k / K).toInt()]) }
                val assign = IntArray(samples.size)
                repeat(6) {
                    val sum = Array(K) { DoubleArray(3) }
                    val cnt = IntArray(K)
                    for (s in samples.indices) {
                        val p = rgb(samples[s])
                        var bk = 0; var bd = Float.MAX_VALUE
                        for (k in 0 until K) {
                            val d = sq(p[0] - means[k][0]) + sq(p[1] - means[k][1]) + sq(p[2] - means[k][2])
                            if (d < bd) { bd = d; bk = k }
                        }
                        assign[s] = bk; cnt[bk]++
                        sum[bk][0] += p[0]; sum[bk][1] += p[1]; sum[bk][2] += p[2]
                    }
                    for (k in 0 until K) if (cnt[k] > 0) for (c in 0..2) means[k][c] = (sum[k][c] / cnt[k]).toFloat()
                }
                val clusters = ArrayList<Cluster>()
                for (k in 0 until K) {
                    val sq = DoubleArray(3); var cnt = 0
                    for (s in samples.indices) if (assign[s] == k) {
                        val p = rgb(samples[s]); cnt++
                        for (c in 0..2) sq[c] += (p[c] - means[k][c]).toDouble().let { it * it }
                    }
                    if (cnt < samples.size / 20) continue // ignore tiny clusters (stray product pixels)
                    val invStd = FloatArray(3) { c -> (1.0 / max(6.0, sqrt(sq[c] / cnt))).toFloat() }
                    val avgSd = (1f / invStd[0] + 1f / invStd[1] + 1f / invStd[2]) / 3f
                    if (avgSd > 40f) continue // a busy cluster is not a usable backdrop
                    clusters.add(Cluster(means[k], invStd))
                }
                return if (clusters.isEmpty()) null else Backdrop(clusters)
            }

            private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF).toFloat(), ((c shr 8) and 0xFF).toFloat(), (c and 0xFF).toFloat())
            private fun sq(x: Float) = x * x
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

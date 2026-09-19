package com.chocolaterabbit.productphotos.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Repairs the model's outline where it cut slightly inside the product.
 *
 * Segmentation models are sometimes confidently wrong along one edge (typically where a dark
 * product meets a dark background), leaving a thin strip of the product outside the mask. For
 * pixels in a narrow band just outside the mask we compare their colour with the product
 * colours right next to them and with the background colours a little further out. A pixel that
 * clearly matches the product and clearly does not match the background is reclaimed.
 *
 * The band is narrow and the additions must stay connected to the model's mask, so on a busy
 * background the worst case is a thin fringe, never a runaway.
 */
object EdgeReclaim {

    private const val CELL = 16
    private const val K_IN = 6
    private const val K_OUT = 12
    private const val MAX_IN_SAMPLES = 256
    private const val MAX_OUT_SAMPLES = 512

    class Params(val radius: Int, val absDistance: Float, val ratio: Float, val passes: Int, val confFloor: Float)

    /** @return pixels to add to the mask. */
    fun compute(px: IntArray, conf: FloatArray, mask0: BooleanArray, w: Int, h: Int, p: Params): BooleanArray {
        val n = w * h
        var mask = mask0
        val added = BooleanArray(n)
        val cellsX = (w + CELL - 1) / CELL
        val cellsY = (h + CELL - 1) / CELL

        repeat(p.passes) {
            val near = MaskRefiner.dilate(mask, w, h, p.radius)
            val far = MaskRefiner.dilate(mask, w, h, 2 * p.radius)
            val band = BooleanArray(n) { i -> near[i] && !mask[i] && conf[i] >= p.confFloor }

            // Which cells have band pixels?
            val cellHasBand = BooleanArray(cellsX * cellsY)
            for (y in 0 until h) for (x in 0 until w) if (band[y * w + x]) cellHasBand[(y / CELL) * cellsX + x / CELL] = true

            val addNow = BooleanArray(n)
            val outMask = BooleanArray(n) { i -> !far[i] }
            for (cy in 0 until cellsY) for (cx in 0 until cellsX) {
                if (!cellHasBand[cy * cellsX + cx]) continue
                val r = p.radius
                val pin = palette(collect(px, mask0, w, h, cx * CELL - r, cy * CELL - r, (cx + 1) * CELL + r, (cy + 1) * CELL + r, MAX_IN_SAMPLES), K_IN)
                    ?: continue
                val r3 = 3 * p.radius
                val pout = palette(collect(px, outMask, w, h, cx * CELL - r3, cy * CELL - r3, (cx + 1) * CELL + r3, (cy + 1) * CELL + r3, MAX_OUT_SAMPLES), K_OUT)

                for (y in max(0, cy * CELL) until min(h, (cy + 1) * CELL)) {
                    for (x in max(0, cx * CELL) until min(w, (cx + 1) * CELL)) {
                        val i = y * w + x
                        if (!band[i]) continue
                        val c = px[i]
                        val din = nearest(c, pin)
                        if (din >= p.absDistance) continue
                        val dout = if (pout == null) Float.MAX_VALUE else nearest(c, pout)
                        if (din < p.ratio * dout) addNow[i] = true
                    }
                }
            }

            // Keep only additions connected to the mask.
            val candidate = BooleanArray(n) { i -> mask[i] || addNow[i] }
            val connected = componentsTouching(candidate, mask, w, h)
            var changed = false
            for (i in 0 until n) if (connected[i] && !mask[i]) { added[i] = true; changed = true }
            if (!changed) return added
            mask = BooleanArray(n) { i -> mask0[i] || added[i] }
        }
        return added
    }

    /** Sub-sampled colours of `sel` pixels inside a window. */
    private fun collect(px: IntArray, sel: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, maxSamples: Int): IntArray {
        val xa = max(0, x0); val ya = max(0, y0); val xb = min(w, x1); val yb = min(h, y1)
        if (xa >= xb || ya >= yb) return IntArray(0)
        var count = 0
        for (y in ya until yb) for (x in xa until xb) if (sel[y * w + x]) count++
        if (count == 0) return IntArray(0)
        val stride = max(1, count / maxSamples)
        val out = IntArray(min(count, maxSamples + 1))
        var seen = 0; var k = 0
        for (y in ya until yb) for (x in xa until xb) {
            if (!sel[y * w + x]) continue
            if (seen % stride == 0 && k < out.size) out[k++] = px[y * w + x]
            seen++
        }
        return out.copyOf(k)
    }

    /** Small k-means palette of the given colours; null when there are none. */
    private fun palette(samples: IntArray, k: Int): Array<FloatArray>? {
        if (samples.isEmpty()) return null
        val pts = Array(samples.size) { rgb(samples[it]) }
        if (pts.size <= k) return pts
        val centers = Array(k) { j -> pts[(pts.size.toLong() * j / k).toInt()].copyOf() }
        val assign = IntArray(pts.size)
        repeat(6) {
            for (s in pts.indices) {
                var best = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) { val d = sq(pts[s], centers[j]); if (d < bd) { bd = d; best = j } }
                assign[s] = best
            }
            val sum = Array(k) { FloatArray(3) }; val cnt = IntArray(k)
            for (s in pts.indices) { val j = assign[s]; cnt[j]++; for (c in 0..2) sum[j][c] += pts[s][c] }
            for (j in 0 until k) if (cnt[j] > 0) for (c in 0..2) centers[j][c] = sum[j][c] / cnt[j]
        }
        return centers
    }

    private fun nearest(c: Int, pal: Array<FloatArray>): Float {
        val p = rgb(c)
        var best = Float.MAX_VALUE
        for (q in pal) { val d = sq(p, q); if (d < best) best = d }
        return sqrt(best)
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF).toFloat(), ((c shr 8) and 0xFF).toFloat(), (c and 0xFF).toFloat())
    private fun sq(a: FloatArray, b: FloatArray): Float { val dr = a[0] - b[0]; val dg = a[1] - b[1]; val db = a[2] - b[2]; return dr * dr + dg * dg + db * db }

    private fun componentsTouching(candidate: BooleanArray, seed: BooleanArray, w: Int, h: Int): BooleanArray {
        val n = w * h
        val out = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        for (i in 0 until n) if (seed[i] && candidate[i] && !out[i]) { out[i] = true; stack[sp++] = i }
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
}

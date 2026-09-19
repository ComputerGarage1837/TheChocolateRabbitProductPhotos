package com.chocolaterabbit.productphotos.processing

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * A conservative one-tap clean-up applied ONLY to product pixels (alpha > 0).
 *  - Auto levels: stretches the product's brightness range (1st..99th percentile) to remove haze.
 *  - Gentle sharpening (unsharp mask) so edges and texture read clearly on a website.
 *  - A touch of saturation so chocolate does not look grey.
 * Transparent pixels are left untouched, so the background is never affected.
 */
object AutoEnhancer {

    fun enhance(cutout: Bitmap): Bitmap {
        val w = cutout.width
        val h = cutout.height
        val px = IntArray(w * h)
        cutout.getPixels(px, 0, w, 0, 0, w, h)

        val (lo, hi) = luminanceRange(px)
        val levelled = IntArray(px.size) { i -> autoLevels(px[i], lo, hi) }
        val sharpened = unsharp(levelled, w, h, amount = 0.45f)
        val result = IntArray(px.size) { i -> saturate(sharpened[i], 1.08f) }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(result, 0, w, 0, 0, w, h)
        }
    }

    private fun luminance(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b).roundToInt()
    }

    /** 1st and 99th percentile luminance of opaque pixels. */
    private fun luminanceRange(px: IntArray): Pair<Int, Int> {
        val hist = IntArray(256)
        var count = 0
        for (c in px) {
            if ((c ushr 24) > 200) { hist[luminance(c)]++; count++ }
        }
        if (count == 0) return 0 to 255
        val lowTarget = (count * 0.01f).toInt()
        val highTarget = (count * 0.99f).toInt()
        var acc = 0
        var lo = 0
        var hi = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowTarget) { lo = i; break }
        }
        acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highTarget) { hi = i; break }
        }
        // Only stretch if there is a meaningful haze; never invert or over-stretch.
        if (hi - lo < 40) return 0 to 255
        return lo to hi
    }

    private fun autoLevels(c: Int, lo: Int, hi: Int): Int {
        val a = c ushr 24
        if (a == 0) return c
        val scale = 255f / (hi - lo)
        // Blend at 70% strength so it stays natural.
        fun lvl(v: Int): Int {
            val stretched = ((v - lo) * scale).coerceIn(0f, 255f)
            return (v * 0.3f + stretched * 0.7f).roundToInt().coerceIn(0, 255)
        }
        val r = lvl((c shr 16) and 0xFF)
        val g = lvl((c shr 8) and 0xFF)
        val b = lvl(c and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun unsharp(src: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = src[i]
                val a = c ushr 24
                if (a == 0) { out[i] = c; continue }
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) {
                    for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                        val nc = src[yy * w + xx]
                        if ((nc ushr 24) == 0) continue // don't pull in background colour at edges
                        sr += (nc shr 16) and 0xFF; sg += (nc shr 8) and 0xFF; sb += nc and 0xFF; n++
                    }
                }
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val br = sr / n; val bg = sg / n; val bb = sb / n
                val nr = (r + (r - br) * amount).roundToInt().coerceIn(0, 255)
                val ng = (g + (g - bg) * amount).roundToInt().coerceIn(0, 255)
                val nb = (b + (b - bb) * amount).roundToInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    private fun saturate(c: Int, factor: Float): Int {
        val a = c ushr 24
        if (a == 0) return c
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val nr = (l + (r - l) * factor).roundToInt().coerceIn(0, 255)
        val ng = (l + (g - l) * factor).roundToInt().coerceIn(0, 255)
        val nb = (l + (b - l) * factor).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}

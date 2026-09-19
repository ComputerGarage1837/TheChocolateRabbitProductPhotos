package com.chocolaterabbit.productphotos.processing

/**
 * Produces the per-pixel foreground confidence for a square photo.
 *
 * Pass 1 runs the model on the whole photo. If the product only fills part of the frame, pass 2
 * runs it again on a crop around the product (so the product fills the model's 1024 px input)
 * and the two results are blended inside the crop. The zoomed pass sees far more detail and
 * gives cleaner edges on cluttered backgrounds.
 */
object SubjectMask {

    private const val ZOOM_IF_AREA_BELOW = 0.65f
    private const val PAD = 0.12f

    fun compute(segmenter: OnnxSegmenter, px: IntArray, w: Int, h: Int): FloatArray {
        val pass1 = segmenter.segment(px, w, h)

        // Bounding box of the confident foreground.
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (pass1[row + x] > 0.5f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return pass1
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw.toFloat() * bh / (w.toFloat() * h) >= ZOOM_IF_AREA_BELOW) return pass1
        if (bw < w / 10 || bh < h / 10) return pass1 // too small to be the product; don't zoom on noise

        // Square crop around the box with padding, clamped to the image.
        val longest = maxOf(bw, bh)
        var side = (longest * (1 + 2 * PAD)).toInt().coerceAtMost(minOf(w, h))
        if (side < 64) side = minOf(64, w, h)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val x0 = (cx - side / 2).coerceIn(0, w - side)
        val y0 = (cy - side / 2).coerceIn(0, h - side)

        val crop = IntArray(side * side)
        for (y in 0 until side) System.arraycopy(px, (y0 + y) * w + x0, crop, y * side, side)
        val pass2 = segmenter.segment(crop, side, side)

        val out = pass1.copyOf()
        for (y in 0 until side) {
            val srcRow = y * side
            val dstRow = (y0 + y) * w + x0
            for (x in 0 until side) {
                out[dstRow + x] = 0.4f * pass1[dstRow + x] + 0.6f * pass2[srcRow + x]
            }
        }
        return out
    }
}

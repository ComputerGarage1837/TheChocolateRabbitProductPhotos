package com.chocolaterabbit.productphotos.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Places the cut-out product on top of the template. Output size = template size (always square). */
object Compositor {

    /**
     * @param brightness -100..100. Applied to the product only (the template is drawn untouched).
     */
    fun compose(
        template: Bitmap,
        cutout: Bitmap,
        autoFit: Boolean,
        fillPercent: Int,
        brightness: Int = 0,
        bounds: Rect? = if (autoFit) subjectBounds(cutout) else null,
    ): Bitmap {
        val side = template.width
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(template, 0f, 0f, null)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (brightness != 0) {
            // Translate RGB by the same amount; alpha row is identity so the cut-out edge is unchanged.
            val offset = brightness.coerceIn(-100, 100) * 1.0f
            val matrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, offset,
                    0f, 1f, 0f, 0f, offset,
                    0f, 0f, 1f, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(matrix)
        }

        if (bounds == null) {
            // Keep the product exactly where it was in the photo.
            canvas.drawBitmap(cutout, null, RectF(0f, 0f, side.toFloat(), side.toFloat()), paint)
        } else {
            // Scale so the product's longest side fills fillPercent of the frame, centred.
            val target = side * (fillPercent.coerceIn(40, 100) / 100f)
            val scale = target / maxOf(bounds.width(), bounds.height()).toFloat()
            val dw = bounds.width() * scale
            val dh = bounds.height() * scale
            val left = (side - dw) / 2f
            val top = (side - dh) / 2f
            canvas.drawBitmap(cutout, bounds, RectF(left, top, left + dw, top + dh), paint)
        }
        return out
    }

    /** Bounding box of pixels that are mostly opaque, with a small margin. Null if nothing found. */
    fun subjectBounds(cutout: Bitmap): Rect? {
        val w = cutout.width
        val h = cutout.height
        val px = IntArray(w * h)
        cutout.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val margin = 2
        val rect = Rect(
            maxOf(0, minX - margin), maxOf(0, minY - margin),
            minOf(w, maxX + 1 + margin), minOf(h, maxY + 1 + margin)
        )
        // Ignore tiny detections (noise) so we don't blow up a speck to full size.
        return if (rect.width() < w / 20 || rect.height() < h / 20) null else rect
    }
}

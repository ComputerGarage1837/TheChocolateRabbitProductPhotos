package com.chocolaterabbit.productphotos.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the ISNet "general use" dichotomous image segmentation model (Apache-2.0, from the DIS
 * project) with ONNX Runtime. Input is a fixed 1024x1024 RGB image; output is a per-pixel
 * foreground probability at the same size, which we resample back to the photo's size.
 */
class OnnxSegmenter(modelFile: File) {

    companion object {
        const val MODEL_FILE = "isnet-general-use.onnx"
        private const val SIZE = 1024
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    )
    private val inputName: String = session.inputNames.first()

    /** @return foreground confidence 0..1 for every pixel of [square] (row-major, width*height). */
    fun segment(square: Bitmap): FloatArray {
        val w = square.width
        val h = square.height

        // Pre-process: resize to 1024, scale to 0..1, subtract 0.5 (ISNet's normalisation), NCHW.
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== square) scaled.recycle()
        val plane = SIZE * SIZE
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input[i] = ((c shr 16) and 0xFF) / 255f - 0.5f
            input[plane + i] = ((c shr 8) and 0xFF) / 255f - 0.5f
            input[2 * plane + i] = (c and 0xFF) / 255f - 0.5f
        }

        val out = FloatArray(plane)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                buffer.rewind()
                buffer.get(out)
            }
        }

        // Post-process: min-max normalise (as the reference implementation does).
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in out) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        for (i in out.indices) out[i] = (out[i] - lo) / range

        return resampleBilinear(out, SIZE, SIZE, w, h)
    }

    private fun resampleBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return src
        val dst = FloatArray(dw * dh)
        val xs = sw.toFloat() / dw
        val ys = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * ys - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, sh - 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * xs - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, sw - 1)
                val wx = fx - x0
                val top = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx
                val bottom = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx
                dst[y * dw + x] = top * (1 - wy) + bottom * wy
            }
        }
        return dst
    }

    fun close() = session.close()
}

package com.chocolaterabbit.productphotos.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.chocolaterabbit.productphotos.data.AppSettings
import com.chocolaterabbit.productphotos.data.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Everything needed to render the finished photo with any combination of the user's two
 * choices (auto clean-up on/off, brightness). The expensive steps (segmentation, enhancement,
 * bounds detection) are done once; rendering a variation is a single canvas draw.
 */
class ProcessedPhoto(
    /** The photo as taken (square-cropped), for the side-by-side comparison. */
    val original: Bitmap,
    private val template: Bitmap,
    private val cutout: Bitmap,
    private val enhancedCutout: Bitmap,
    private val bounds: Rect?,
    private val fillPercent: Int,
) {
    fun render(enhanced: Boolean, brightness: Int): Bitmap =
        Compositor.compose(
            template = template,
            cutout = if (enhanced) enhancedCutout else cutout,
            autoFit = bounds != null,
            fillPercent = fillPercent,
            brightness = brightness,
            bounds = bounds,
        )
}

/**
 * A batch-processed photo: both versions already rendered to PNG files in the cache (so that a
 * large batch does not hold dozens of full-size bitmaps in memory) plus small previews.
 */
class BatchResult(
    val plainFile: File,
    val enhancedFile: File,
    val plainThumb: Bitmap,
    val enhancedThumb: Bitmap,
    val originalThumb: Bitmap,
) {
    fun file(enhanced: Boolean) = if (enhanced) enhancedFile else plainFile
    fun thumb(enhanced: Boolean) = if (enhanced) enhancedThumb else plainThumb
    fun deleteFiles() { plainFile.delete(); enhancedFile.delete() }
}

/** Photo in -> background removed -> (optional product clean-up) -> placed on template. */
class ProductPhotoPipeline(
    private val context: Context,
    private val templateStore: TemplateStore,
    model: ModelInstaller,
) {
    private val remover = BackgroundRemover { model.segmenter }
    private val THUMB = 400

    /** Batch variant: renders both versions straight to PNG files and keeps only thumbnails. */
    suspend fun processToFiles(photo: Uri, settings: AppSettings, id: String): BatchResult = withContext(Dispatchers.Default) {
        val processed = process(photo, settings)
        val dir = File(context.cacheDir, "batch").apply { mkdirs() }
        val plain = processed.render(enhanced = false, brightness = 0)
        val enhanced = processed.render(enhanced = true, brightness = 0)
        val plainFile = File(dir, "$id-plain.png").also { f -> FileOutputStream(f).use { plain.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val enhancedFile = File(dir, "$id-enhanced.png").also { f -> FileOutputStream(f).use { enhanced.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val result = BatchResult(
            plainFile = plainFile,
            enhancedFile = enhancedFile,
            plainThumb = Bitmap.createScaledBitmap(plain, THUMB, THUMB, true),
            enhancedThumb = Bitmap.createScaledBitmap(enhanced, THUMB, THUMB, true),
            originalThumb = Bitmap.createScaledBitmap(processed.original, THUMB, THUMB, true),
        )
        plain.recycle(); enhanced.recycle(); processed.original.recycle()
        result
    }

    suspend fun process(photo: Uri, settings: AppSettings): ProcessedPhoto = withContext(Dispatchers.Default) {
        val square = ImageLoader.loadSquare(context, photo)
        val cutout = remover.cutOut(square, settings.cutoutSensitivity)
        val template = templateStore.loadTemplate(settings.useCustomTemplate)
        val bounds = if (settings.autoFitProduct) Compositor.subjectBounds(cutout) else null
        ProcessedPhoto(
            original = square,
            template = template,
            cutout = cutout,
            enhancedCutout = AutoEnhancer.enhance(cutout),
            bounds = bounds,
            fillPercent = settings.productFillPercent,
        )
    }

}

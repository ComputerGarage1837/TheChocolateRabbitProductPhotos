package com.chocolaterabbit.productphotos.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.chocolaterabbit.productphotos.data.AppSettings
import com.chocolaterabbit.productphotos.data.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/** Photo in -> background removed -> (optional product clean-up) -> placed on template. */
class ProductPhotoPipeline(
    private val context: Context,
    private val templateStore: TemplateStore,
) {
    private val remover = BackgroundRemover()

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

    fun close() = remover.close()
}

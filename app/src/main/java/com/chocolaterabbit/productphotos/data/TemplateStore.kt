package com.chocolaterabbit.productphotos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.chocolaterabbit.productphotos.R
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the background template. The bundled template lives in res/drawable;
 * a user-chosen replacement is copied into internal storage so it survives
 * even if the original file is deleted from the phone.
 */
class TemplateStore(private val context: Context) {

    private val customFile: File get() = File(context.filesDir, "custom_template.png")

    fun hasCustomTemplate(): Boolean = customFile.exists()

    fun customTemplateFile(): File? = customFile.takeIf { it.exists() }

    /** Loads whichever template is active. Always returns a square bitmap. */
    fun loadTemplate(useCustom: Boolean): Bitmap {
        val raw = if (useCustom && customFile.exists()) {
            BitmapFactory.decodeFile(customFile.absolutePath)
        } else {
            null
        } ?: BitmapFactory.decodeResource(context.resources, R.drawable.template_default)
        return squareCrop(raw)
    }

    /**
     * Copies the picked image into internal storage as PNG (keeps transparency if present).
     * Returns false if the image could not be decoded.
     */
    fun importCustomTemplate(uri: Uri): Boolean {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return false
        val square = squareCrop(bitmap)
        FileOutputStream(customFile).use { out ->
            square.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return true
    }

    fun removeCustomTemplate() {
        customFile.delete()
    }

    private fun squareCrop(src: Bitmap): Bitmap {
        if (src.width == src.height) return src
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }
}

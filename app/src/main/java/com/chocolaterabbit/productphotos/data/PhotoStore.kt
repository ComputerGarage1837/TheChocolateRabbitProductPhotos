package com.chocolaterabbit.productphotos.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One finished product photo in the in-app gallery. */
data class SavedPhoto(val file: File) {
    val name: String get() = file.nameWithoutExtension
    val takenAt: Long get() = file.lastModified()
}

/**
 * Stores finished photos in the app's private "edited" folder (this is the in-app gallery)
 * and optionally mirrors them into the phone's shared Pictures folder.
 */
class PhotoStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "edited").apply { mkdirs() }

    fun listPhotos(): List<SavedPhoto> =
        dir.listFiles { f -> f.isFile && f.extension.equals("png", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { SavedPhoto(it) }
            ?: emptyList()

    /** Saves as lossless PNG so repeated edits/exports never degrade the product. */
    fun save(bitmap: Bitmap, alsoToDeviceGallery: Boolean): SavedPhoto {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "product_$stamp.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (alsoToDeviceGallery) {
            runCatching { exportToMediaStore(bitmap, file.nameWithoutExtension) }
        }
        return SavedPhoto(file)
    }

    fun delete(photo: SavedPhoto) {
        photo.file.delete()
    }

    fun shareUri(photo: SavedPhoto): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo.file)

    private fun exportToMediaStore(bitmap: Bitmap, displayName: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Chocolate Rabbit Products"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }
}

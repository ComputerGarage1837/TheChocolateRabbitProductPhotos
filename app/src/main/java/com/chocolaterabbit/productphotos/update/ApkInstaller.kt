package com.chocolaterabbit.productphotos.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.chocolaterabbit.productphotos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Progress of an update download, for the dialog. */
sealed interface DownloadState {
    data object Starting : DownloadState
    data object WaitingForNetwork : DownloadState
    data class Progress(val downloaded: Long, val total: Long) : DownloadState
    data object Verifying : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Downloads a release APK with DownloadManager, verifies the SHA-256 from the update feed,
 * and hands the file to the package installer.
 */
object ApkInstaller {

    /** True when Android will let this app open the installer; false means the user must allow it first. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows this app to install updates. */
    fun requestInstallPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Emits progress until the APK is downloaded and verified (Done) or something fails. */
    fun download(context: Context, release: UpdateChecker.Release): Flow<DownloadState> = flow {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        dir.mkdirs()
        val file = File(dir, release.assetName)
        if (file.exists()) file.delete()

        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Chocolate Rabbit Photos v${release.versionName}")
            .setDescription("Downloading update…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
            .addRequestHeader("User-Agent", "ChocolateRabbitProductPhotos")

        val id = try {
            dm.enqueue(req)
        } catch (e: Exception) {
            emit(DownloadState.Failed("Couldn't start download: ${e.message}"))
            return@flow
        }
        emit(DownloadState.Starting)

        try {
            var status = DownloadManager.STATUS_PENDING
            var reason = 0
            while (true) {
                var downloaded = 0L
                var total = release.sizeBytes
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) {
                        status = DownloadManager.STATUS_FAILED
                        reason = -1
                        return@use
                    }
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val t = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (t > 0) total = t
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> break
                    DownloadManager.STATUS_PAUSED -> emit(DownloadState.WaitingForNetwork)
                    DownloadManager.STATUS_PENDING -> emit(DownloadState.Starting)
                    else -> emit(DownloadState.Progress(downloaded, total))
                }
                delay(500)
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                emit(DownloadState.Failed(if (reason == -1) "Download cancelled." else "Download failed (${describe(reason)}). Please try again."))
                return@flow
            }
            emit(DownloadState.Verifying)
            if (!verify(file, release)) {
                file.delete()
                emit(DownloadState.Failed("The downloaded update didn't match the release. Please try again."))
                return@flow
            }
            emit(DownloadState.Done(file))
        } catch (e: Exception) {
            // The collector went away (dialog cancelled): stop the download.
            runCatching { dm.remove(id) }
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun verify(file: File, release: UpdateChecker.Release): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) return false
        val expected = release.sha256 ?: return true
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expected
    }

    private fun describe(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not available"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "network data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough space"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server error"
        404 -> "APK not found on the release"
        else -> "error $reason"
    }

    fun install(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

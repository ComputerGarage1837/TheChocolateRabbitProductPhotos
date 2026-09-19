package com.chocolaterabbit.productphotos.processing

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the on-device background-removal model is on the phone yet. */
sealed interface ModelState {
    data object Checking : ModelState
    data object Ready : ModelState
    /** percent is -1 while the download size is not known yet. */
    data class Downloading(val percent: Int) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The segmentation model is provided by Google Play services and downloaded once (about 10 MB).
 * Play services only fetches it automatically for apps installed from the Play Store, so a
 * side-loaded APK has to ask for it. This class does that and reports progress.
 */
class ModelInstaller(context: Context) {

    private val appContext = context.applicationContext
    private val client = ModuleInstall.getClient(appContext)
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
    )

    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state

    /** Checks availability and starts the download if needed. Safe to call repeatedly. */
    fun ensureInstalled() {
        val current = _state.value
        if (current is ModelState.Ready || current is ModelState.Downloading) return
        _state.value = ModelState.Checking

        client.areModulesAvailable(segmenter)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    _state.value = ModelState.Ready
                } else {
                    startDownload()
                }
            }
            .addOnFailureListener { e ->
                _state.value = ModelState.Failed(describe(e))
            }
    }

    private fun startDownload() {
        _state.value = ModelState.Downloading(-1)
        val listener = InstallStatusListener { update: ModuleInstallStatusUpdate ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> _state.value = ModelState.Ready
                InstallState.STATE_FAILED -> _state.value =
                    ModelState.Failed("Google Play services could not download the model. Check the connection and try again.")
                InstallState.STATE_CANCELED -> _state.value = ModelState.Failed("Model download was cancelled.")
                else -> {
                    val info = update.progressInfo
                    val pct = if (info != null && info.totalBytesToDownload > 0)
                        (info.bytesDownloaded * 100 / info.totalBytesToDownload).toInt() else -1
                    _state.value = ModelState.Downloading(pct)
                }
            }
        }
        val request = ModuleInstallRequest.newBuilder()
            .addApi(segmenter)
            .setListener(listener)
            .build()
        client.installModules(request)
            .addOnSuccessListener { response ->
                // Already present (race with another download) -> ready right away.
                if (response.areModulesAlreadyInstalled()) _state.value = ModelState.Ready
            }
            .addOnFailureListener { e -> _state.value = ModelState.Failed(describe(e)) }
    }

    private fun describe(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return "Could not get the background-removal model from Google Play services.\n\n" +
            "This phone needs Google Play services and an internet connection for the first launch only.\n\n($msg)"
    }
}

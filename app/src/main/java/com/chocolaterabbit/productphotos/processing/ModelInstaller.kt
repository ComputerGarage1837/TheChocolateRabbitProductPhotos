package com.chocolaterabbit.productphotos.processing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Whether the background-removal model is loaded and ready. */
sealed interface ModelState {
    data object Checking : ModelState
    data object Ready : ModelState
    /** percent is -1 while the amount of work is not known. */
    data class Downloading(val percent: Int) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The segmentation model ships inside the APK (assets/models). On first launch it is copied out
 * to internal storage once (ONNX Runtime wants a file path), then loaded. Nothing is downloaded.
 */
class ModelInstaller(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state

    @Volatile
    var segmenter: OnnxSegmenter? = null
        private set

    /** Prepares and loads the model. Safe to call repeatedly. */
    fun ensureInstalled() {
        val current = _state.value
        if (current is ModelState.Ready || current is ModelState.Downloading) return
        _state.value = ModelState.Checking
        scope.launch {
            try {
                val file = extractModel()
                _state.value = ModelState.Downloading(-1)
                segmenter = OnnxSegmenter(file)
                _state.value = ModelState.Ready
            } catch (e: Throwable) {
                _state.value = ModelState.Failed(
                    "Couldn't load the background remover.\n\n(${e.message ?: e.javaClass.simpleName})"
                )
            }
        }
    }

    /** Copies the model out of the APK into internal storage, with progress. */
    private fun extractModel(): File {
        val dir = File(appContext.filesDir, "models").apply { mkdirs() }
        val target = File(dir, OnnxSegmenter.MODEL_FILE)
        val fd = appContext.assets.openFd("models/${OnnxSegmenter.MODEL_FILE}")
        val total = fd.length
        fd.close()
        if (target.exists() && target.length() == total) return target

        val tmp = File(dir, "${OnnxSegmenter.MODEL_FILE}.part")
        appContext.assets.open("models/${OnnxSegmenter.MODEL_FILE}").use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (total > 0) _state.value = ModelState.Downloading((copied * 100 / total).toInt())
                }
            }
        }
        if (!tmp.renameTo(target)) throw IllegalStateException("Could not store the model")
        return target
    }
}

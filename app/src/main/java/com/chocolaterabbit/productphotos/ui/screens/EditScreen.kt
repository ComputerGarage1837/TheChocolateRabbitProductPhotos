package com.chocolaterabbit.productphotos.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chocolaterabbit.productphotos.AppContainer
import com.chocolaterabbit.productphotos.data.AppSettings
import com.chocolaterabbit.productphotos.processing.ModelState
import com.chocolaterabbit.productphotos.processing.ProcessedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface EditState {
    data object Working : EditState
    data class Ready(val photo: ProcessedPhoto) : EditState
    data class Failed(val message: String) : EditState
}

/**
 * Shows the finished photo. The user only chooses between "As shot" and "Auto clean-up",
 * then saves. No manual sliders on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    photoUri: Uri,
    container: AppContainer,
    onSaved: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EditState>(EditState.Working) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var useEnhanced by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var brightness by remember { mutableIntStateOf(0) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val modelState by container.model.state.collectAsState()

    LaunchedEffect(photoUri, reloadKey) {
        state = EditState.Working
        settings = container.settings.settings.first()
        useEnhanced = settings.autoEnhanceByDefault
        // Make sure the background-removal model is on the phone; wait for the download if not.
        container.model.ensureInstalled()
        val ready = container.model.state.first { it is ModelState.Ready || it is ModelState.Failed }
        if (ready is ModelState.Failed) {
            state = EditState.Failed(ready.message)
            return@LaunchedEffect
        }
        state = runCatching { container.pipeline.process(photoUri, settings) }
            .fold(
                onSuccess = { EditState.Ready(it) },
                onFailure = { EditState.Failed(friendlyError(it)) }
            )
    }

    // Re-render whenever the user changes one of the two choices. Cheap: a single canvas draw.
    LaunchedEffect(state, useEnhanced, brightness) {
        val ready = state as? EditState.Ready ?: return@LaunchedEffect
        rendered = withContext(Dispatchers.Default) { ready.photo.render(useEnhanced, brightness) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                EditState.Working -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            when (val m = modelState) {
                                is ModelState.Downloading -> {
                                    if (m.percent < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(progress = { m.percent / 100f }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Downloading the background remover (one time only)" +
                                            if (m.percent >= 0) " ${m.percent}%" else "",
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                ModelState.Checking -> {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(16.dp))
                                    Text("Checking background remover…")
                                }
                                else -> {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(16.dp))
                                    Text("Removing background…")
                                }
                            }
                        }
                    }
                }

                is EditState.Failed -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Text(s.message, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRetake) { Text("Try another photo") }
                        Button(onClick = { container.model.ensureInstalled(); reloadKey++ }) { Text("Try again") }
                    }
                }

                is EditState.Ready -> {
                    val shown = rendered
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (shown != null) {
                            Image(
                                bitmap = shown.asImageBitmap(),
                                contentDescription = "Result",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !useEnhanced,
                            onClick = { useEnhanced = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("As shot") }
                        SegmentedButton(
                            selected = useEnhanced,
                            onClick = { useEnhanced = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Auto clean-up") }
                    }
                    Text(
                        "Auto clean-up only touches the product. The background is always replaced with the template.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Brightness", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (brightness > 0) "+$brightness" else "$brightness",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { brightness = 0 }, enabled = brightness != 0) { Text("Reset") }
                    }
                    Slider(
                        value = brightness.toFloat(),
                        onValueChange = { brightness = it.toInt() },
                        valueRange = -60f..60f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.weight(1f))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f), enabled = !saving) {
                            Text("Retake")
                        }
                        Button(
                            onClick = {
                                val toSave = shown ?: return@Button
                                saving = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.photos.save(toSave, settings.saveToDeviceGallery)
                                    }
                                    saving = false
                                    onSaved()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !saving && shown != null,
                        ) {
                            Text(if (saving) "Saving…" else "Save")
                        }
                    }
                }
            }
        }
    }
}

private fun friendlyError(t: Throwable): String {
    val msg = t.message ?: ""
    return when {
        msg.contains("No subject", ignoreCase = true) ->
            "Couldn't find a product in that photo. Try again with the product clearly in the square."
        msg.contains("model", ignoreCase = true) || msg.contains("download", ignoreCase = true) ||
            msg.contains("module", ignoreCase = true) ->
            "The background remover isn't ready on this phone yet. Tap \"Try again\" to download it (needs internet once).\n\n($msg)"
        else -> "Something went wrong while processing the photo.\n\n$msg"
    }
}

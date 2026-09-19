package com.chocolaterabbit.productphotos.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.chocolaterabbit.productphotos.AppContainer
import com.chocolaterabbit.productphotos.data.AppSettings
import com.chocolaterabbit.productphotos.processing.BatchResult
import com.chocolaterabbit.productphotos.processing.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ItemState {
    data object Pending : ItemState
    data object Working : ItemState
    data class Done(val result: BatchResult) : ItemState
    data class Failed(val message: String) : ItemState
}

private class BatchItem(val uri: Uri) {
    var state by mutableStateOf<ItemState>(ItemState.Pending)
    var useEnhanced by mutableStateOf(false)
    var showOriginal by mutableStateOf(false)
}

/**
 * Processes many photos in a row with the same template and settings. Each finished photo has
 * its own "Auto clean-up" switch; "Save all" writes every photo in its chosen version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(uris: List<Uri>, container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<BatchItem>().apply { addAll(uris.map { BatchItem(it) }) } }
    var settings by remember { mutableStateOf(AppSettings()) }
    var saving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val doneCount = items.count { it.state is ItemState.Done }
    val failedCount = items.count { it.state is ItemState.Failed }
    val finished = doneCount + failedCount == items.size

    // Process one after another (the model is heavy; running two at once would just thrash).
    LaunchedEffect(Unit) {
        settings = container.settings.settings.first()
        val defaultEnhanced = settings.autoEnhanceByDefault
        items.forEach { it.useEnhanced = defaultEnhanced }
        container.model.ensureInstalled()
        val ready = container.model.state.first { it is ModelState.Ready || it is ModelState.Failed }
        if (ready is ModelState.Failed) {
            items.forEach { it.state = ItemState.Failed(ready.message) }
            return@LaunchedEffect
        }
        items.forEachIndexed { index, item ->
            item.state = ItemState.Working
            item.state = runCatching { container.pipeline.processToFiles(item.uri, settings, "b${System.currentTimeMillis()}_$index") }
                .fold({ ItemState.Done(it) }, { ItemState.Failed(it.message ?: "Failed") })
        }
    }

    // Clean up the cache files when leaving.
    DisposableEffect(Unit) {
        onDispose { items.forEach { (it.state as? ItemState.Done)?.result?.deleteFiles() } }
    }

    fun saveAll() {
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    val done = item.state as? ItemState.Done ?: return@forEach
                    container.photos.saveFile(done.result.file(item.useEnhanced), settings.saveToDeviceGallery)
                }
            }
            saving = false
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (finished) "Batch: ${items.size} photos" else "Batch: $doneCount of ${items.size}") },
                navigationIcon = {
                    IconButton(onClick = { if (doneCount > 0) confirmLeave = true else onDone() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (!finished) {
                    LinearProgressIndicator(
                        progress = { (doneCount + failedCount).toFloat() / items.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = { saveAll() },
                    enabled = finished && doneCount > 0 && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            saving -> "Saving…"
                            !finished -> "Processing… ($doneCount of ${items.size} done)"
                            else -> "Save all ($doneCount)"
                        }
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.uri.toString() + it.hashCode() }) { index, item ->
                BatchRow(index + 1, item, onRemove = {
                    (item.state as? ItemState.Done)?.result?.deleteFiles()
                    items.remove(item)
                })
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("The processed photos will be discarded.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onDone() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun BatchRow(number: Int, item: BatchItem, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (val s = item.state) {
                    is ItemState.Done -> Image(
                        bitmap = (if (item.showOriginal) s.result.originalThumb else s.result.thumb(item.useEnhanced)).asImageBitmap(),
                        contentDescription = "Photo $number",
                        modifier = Modifier.fillMaxSize(),
                    )
                    ItemState.Working -> CircularProgressIndicator()
                    ItemState.Pending -> Text("Waiting", style = MaterialTheme.typography.bodySmall)
                    is ItemState.Failed -> Text("Failed", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Photo $number", style = MaterialTheme.typography.titleSmall)
                when (val s = item.state) {
                    is ItemState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto clean-up", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = item.useEnhanced, onCheckedChange = { item.useEnhanced = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show original", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(checked = item.showOriginal, onCheckedChange = { item.showOriginal = it })
                        }
                    }
                    is ItemState.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall)
                    ItemState.Working -> Text("Removing background…", style = MaterialTheme.typography.bodySmall)
                    ItemState.Pending -> Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
        }
    }
}

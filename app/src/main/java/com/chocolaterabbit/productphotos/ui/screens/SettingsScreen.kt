package com.chocolaterabbit.productphotos.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.chocolaterabbit.productphotos.AppContainer
import com.chocolaterabbit.productphotos.data.AppSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    var templateVersion by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val templatePreview = remember(settings.useCustomTemplate, templateVersion) {
        container.templates.loadTemplate(settings.useCustomTemplate)
    }

    val pickTemplate = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val ok = container.templates.importCustomTemplate(uri)
            if (ok) {
                scope.launch { container.settings.setUseCustomTemplate(true) }
                templateVersion++
                message = "Template updated."
            } else {
                message = "Couldn't read that image."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("Background template", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = templatePreview.asImageBitmap(),
                    contentDescription = "Current template",
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(
                        if (settings.useCustomTemplate && container.templates.hasCustomTemplate())
                            "Using your custom template" else "Using the built-in cream template",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Any square image works. Non-square images are centre-cropped.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    pickTemplate.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose new template") }
                Spacer(Modifier.size(12.dp))
                OutlinedButton(
                    enabled = settings.useCustomTemplate,
                    onClick = {
                        container.templates.removeCustomTemplate()
                        scope.launch { container.settings.setUseCustomTemplate(false) }
                        templateVersion++
                        message = "Back to the built-in template."
                    }
                ) { Text("Reset") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SettingRow(
                title = "Start with Auto clean-up",
                subtitle = "Pre-select the cleaned-up version on the review screen. You can still switch to \"As shot\".",
                checked = settings.autoEnhanceByDefault,
                onChange = { scope.launch { container.settings.setAutoEnhanceByDefault(it) } },
            )

            SettingRow(
                title = "Centre and size the product",
                subtitle = "Keeps every product the same size and position on the template so the website looks uniform.",
                checked = settings.autoFitProduct,
                onChange = { scope.launch { container.settings.setAutoFitProduct(it) } },
            )

            if (settings.autoFitProduct) {
                Text(
                    "Product size: ${settings.productFillPercent}% of the frame",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = settings.productFillPercent.toFloat(),
                    onValueChange = { scope.launch { container.settings.setProductFillPercent(it.toInt()) } },
                    valueRange = 40f..100f,
                    steps = 11,
                )
            }

            SettingRow(
                title = "Also save to phone gallery",
                subtitle = "Copies each finished photo into Pictures / Chocolate Rabbit Products.",
                checked = settings.saveToDeviceGallery,
                onChange = { scope.launch { container.settings.setSaveToDeviceGallery(it) } },
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

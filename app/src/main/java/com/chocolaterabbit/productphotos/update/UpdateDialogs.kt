package com.chocolaterabbit.productphotos.update

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chocolaterabbit.productphotos.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Drives the whole update flow from a single composable:
 * check -> "Update available" dialog -> download progress -> installer.
 *
 * @param manual true when the user pressed "Check for updates": always reports the outcome
 *   and ignores a skipped version. False for the silent check on app start.
 * @param onFinished called when there is nothing more to show.
 */
@Composable
fun UpdateFlow(settings: SettingsRepository, manual: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (manual) Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show()
        val latest = try {
            UpdateChecker.fetchLatest()
        } catch (e: Exception) {
            if (manual) Toast.makeText(context, "Couldn't check for updates: ${e.message}", Toast.LENGTH_LONG).show()
            onFinished(); return@LaunchedEffect
        }
        if (latest.versionCode <= UpdateChecker.installedVersionCode) {
            if (manual) Toast.makeText(context, "You're on the latest version (v${UpdateChecker.installedVersionName})", Toast.LENGTH_LONG).show()
            onFinished(); return@LaunchedEffect
        }
        if (!manual && settings.settings.first().skippedUpdateVersion == latest.versionName) {
            onFinished(); return@LaunchedEffect
        }
        release = latest
    }

    val r = release ?: return

    if (!downloading) {
        val size = if (r.sizeBytes > 0) " (${UpdateChecker.formatSize(r.sizeBytes)})" else ""
        AlertDialog(
            onDismissRequest = onFinished,
            title = { Text("Update available: v${r.versionName}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Version ${r.versionName}$size is available. You have v${UpdateChecker.installedVersionName}.")
                    Spacer(Modifier.height(12.dp))
                    Text("What's new:", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(r.notes.ifBlank { "No release notes provided." }, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ApkInstaller.canInstall(context)) {
                        downloading = true
                    } else {
                        Toast.makeText(context, "Allow this app to install updates, then press Update again.", Toast.LENGTH_LONG).show()
                        ApkInstaller.requestInstallPermission(context)
                    }
                }) { Text("Update now") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = onFinished) { Text("Later") }
                    SkipButton(settings, r.versionName, onFinished)
                }
            },
        )
    } else {
        DownloadDialog(release = r, onFinished = onFinished)
    }
}

@Composable
private fun SkipButton(settings: SettingsRepository, versionName: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(skip) {
        if (skip) {
            settings.setSkippedUpdateVersion(versionName)
            Toast.makeText(context, "v$versionName skipped. You'll be asked again for the next version.", Toast.LENGTH_LONG).show()
            onFinished()
        }
    }
    TextButton(onClick = { skip = true }) { Text("Skip this version") }
}

@Composable
private fun DownloadDialog(release: UpdateChecker.Release, onFinished: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<DownloadState>(DownloadState.Starting) }

    LaunchedEffect(release) {
        ApkInstaller.download(context, release).collect { s ->
            state = s
            when (s) {
                is DownloadState.Done -> {
                    if (!ApkInstaller.install(context, s.file)) {
                        Toast.makeText(context, "Couldn't open the installer.", Toast.LENGTH_LONG).show()
                    }
                    onFinished()
                }
                is DownloadState.Failed -> {
                    Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                    onFinished()
                }
                else -> Unit
            }
        }
    }

    AlertDialog(
        onDismissRequest = { /* not cancellable by tapping outside */ },
        title = { Text("Downloading v${release.versionName}") },
        text = {
            Column {
                val s = state
                val (label, fraction) = when (s) {
                    DownloadState.Starting -> "Starting download…" to null
                    DownloadState.WaitingForNetwork -> "Waiting for network…" to null
                    is DownloadState.Progress ->
                        if (s.total > 0) "${UpdateChecker.formatSize(s.downloaded)} of ${UpdateChecker.formatSize(s.total)}" to
                            (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f)
                        else UpdateChecker.formatSize(s.downloaded) to null
                    DownloadState.Verifying -> "Verifying download…" to null
                    is DownloadState.Done -> "Opening installer…" to 1f
                    is DownloadState.Failed -> s.message to null
                }
                Text(label)
                Spacer(Modifier.height(12.dp))
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onFinished) { Text("Cancel") } },
    )
}

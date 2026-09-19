package com.chocolaterabbit.productphotos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen camera with a square guide. The captured file is handed to the edit screen. */
@Composable
fun CameraScreen(onCaptured: (Uri) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is needed to take product photos.", textAlign = TextAlign.Center)
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) {
                Text("Allow camera")
            }
            Button(onClick = onBack, Modifier.padding(top = 8.dp)) { Text("Back") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(lensFacing) {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture) }
            .onFailure { error = "Could not start camera: ${it.message}" }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Square guide: darken everything outside the 1:1 crop so the product is framed correctly.
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            val top = (size.height - side) / 2f
            val left = (size.width - side) / 2f
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(0f, 0f), Size(size.width, top))
            drawRect(shade, Offset(0f, top + side), Size(size.width, size.height - top - side))
            drawRect(shade, Offset(0f, top), Size(left, side))
            drawRect(shade, Offset(left + side, top), Size(size.width - left - side, side))
            drawRect(Color.White.copy(alpha = 0.8f), Offset(left, top), Size(side, side), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let {
                Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
            Text(
                "Keep the product inside the square",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    IconButton(
                        onClick = {
                            capturing = true
                            val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            imageCapture.takePicture(
                                options,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        capturing = false
                                        onCaptured(Uri.fromFile(file))
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        capturing = false
                                        error = "Capture failed: ${exception.message}"
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.White),
                    ) {}
                }
            }
            Box(Modifier.height(8.dp))
        }
    }
}

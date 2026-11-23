package io.github.bropines.lens.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.util.concurrent.Executors

actual val cameraAvailable: Boolean = true

@Composable
actual fun CameraScreen(
    services: PlatformServices,
    onCaptured: (PlatformFile) -> Unit,
    onPickImages: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            LiveCamera(services, onCaptured)
        } else if (asked) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нужен доступ к камере, чтобы переводить на лету.", color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Разрешить")
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Color.White) }
        }
        IconButton(
            onClick = onPickImages,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 32.dp, bottom = 44.dp)
                .size(56.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
        ) { Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Из галереи", tint = Color.White) }
    }
}

@Composable
private fun LiveCamera(services: PlatformServices, onCaptured: (PlatformFile) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<ScannedCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor, CodeAnalyzer { found -> if (found != code) code = found }) }
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        try {
            awaitCancellation()
        } finally {
            provider.unbindAll()
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize()) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.fillMaxSize().pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val state = camera?.cameraInfo?.zoomState?.value ?: return@detectTransformGestures
                        val next = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        camera?.cameraControl?.setZoomRatio(next)
                    }
                },
            )
        }

        // Bottom right, mirroring the gallery button: both within the thumb's reach beside the shutter.
        IconButton(
            onClick = { torch = !torch },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 32.dp, bottom = 44.dp)
                .size(56.dp).background(Color.White.copy(alpha = if (torch) 0.9f else 0.18f), CircleShape),
        ) {
            Icon(
                if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = "Вспышка",
                tint = if (torch) Color.Black else Color.White,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedVisibility(code != null, enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 }, exit = fadeOut(tween(90))) {
                code?.let { CodeCard(it, services, onDismiss = { code = null }) }
            }
            Text(
                error ?: "Наведите на текст и нажмите",
                color = if (error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
            )
            Shutter(busy = capturing) {
                if (capturing) return@Shutter
                capturing = true
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            capturing = false
                            onCaptured(PlatformFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            error = "Не удалось снять: ${exception.message ?: exception.imageCaptureError}"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
            .background(if (busy) Color.White.copy(alpha = 0.5f) else Color.White, CircleShape)
            .clickable(enabled = !busy, onClickLabel = "Снять", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (busy) LoadingIndicator(Modifier.size(36.dp)) }
}

/** What the camera read: open a link, copy text, join Wi-Fi by copying the password. */
@Composable
private fun CodeCard(code: ScannedCode, services: PlatformServices, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                val wifi = code.wifi
                Text(
                    when {
                        wifi != null -> "Wi-Fi: ${wifi.first}"
                        else -> code.text
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (code.isUrl) {
                    Button(onClick = { services.openUrl(code.text) }) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.padding(end = 6.dp)); Text("Открыть")
                    }
                }
                val wifiPassword = code.wifi?.second
                FilledTonalButton(onClick = { services.copyText(wifiPassword ?: code.text) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 6.dp))
                    Text(if (wifiPassword != null) "Пароль" else "Копировать")
                }
                FilledTonalButton(onClick = onDismiss) { Text("Скрыть") }
            }
        }
    }
}

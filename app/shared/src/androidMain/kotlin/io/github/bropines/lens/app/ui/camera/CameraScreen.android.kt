package io.github.bropines.lens.app.ui.camera

import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.max
import kotlin.math.min
import androidx.camera.core.resolutionselector.ResolutionSelector
import android.content.res.Configuration
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
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
                Text(stringResource(Res.string.camera_permission), color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(Res.string.camera_allow))
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.back), tint = Color.White) }
        }
        val turned = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        IconButton(
            onClick = onPickImages,
            modifier = (
                if (turned) {
                    // Turned: in the column at the right edge, below the shutter.
                    Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = SIDE_BUTTON_END).offset(y = SIDE_BUTTON_OFFSET)
                } else {
                    Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 32.dp, bottom = 44.dp)
                }
            ).size(56.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
        ) { Icon(Icons.Rounded.PhotoLibrary, contentDescription = stringResource(Res.string.camera_from_gallery), tint = Color.White) }
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
    // Where the last code was seen in the frame; cleared shortly after it leaves the view.
    var codeBox by remember { mutableStateOf<CodeBox?>(null) }
    LaunchedEffect(codeBox?.seenAt) {
        if (codeBox != null) { delay(CODE_BOX_LINGER_MS); codeBox = null }
    }
    // What went wrong taking the last photo; the UI words it.
    var error by remember { mutableStateOf<String?>(null) }
    // Preview and photo both 4:3, the sensor's own shape: the viewfinder below is
    // sized to the frame instead of cropped to the screen, so what it shows is the photo.
    val fourByThree = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(fourByThree)
            .build()
    }
    // Maps viewfinder taps to surface coordinates, whatever the crop and rotation.
    val transformer = remember { MutableCoordinateTransformer() }
    // Exposure compensation as a device index (EV = index * step), screen preset, metering point.
    var evIndex by remember { mutableIntStateOf(0) }
    var screenMode by remember { mutableStateOf(false) }
    var showExposure by remember { mutableStateOf(false) }
    var exposureTouch by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<FocusMark?>(null) }
    val exposure = camera?.cameraInfo?.exposureState?.takeIf { it.isExposureCompensationSupported }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().setResolutionSelector(fourByThree).build().apply { setSurfaceProvider { surfaceRequest = it } }
        // 4:3 like the preview, so a code's place in the frame is its place in the
        // viewfinder; and larger than the 640×480 default, or small codes are a few pixels.
        val analysisSize = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSize)
            .build()
            .apply {
                setAnalyzer(executor, CodeAnalyzer { found, corners ->
                    // Called on the analysis thread; state is read by the UI.
                    ContextCompat.getMainExecutor(context).execute {
                        if (found != code) code = found
                        codeBox = CodeBox(corners, System.nanoTime())
                    }
                })
            }
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.unbindAll()
        val bound = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        camera = bound
        try {
            awaitCancellation()
        } finally {
            // Leave the camera as we found it: continuous AF/AE, no compensation.
            bound.cameraControl.cancelFocusAndMetering()
            bound.cameraControl.setExposureCompensationIndex(0)
            // Only this screen's own use cases: when the phone turns, the activity is recreated and
            // this runs after the new screen has bound its camera, which unbindAll() would turn off.
            provider.unbind(preview, capture, analysis)
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(camera, evIndex) {
        if (exposure != null) camera?.cameraControl?.setExposureCompensationIndex(evIndex)
    }
    // The slider tucks itself away a few seconds after the last touch.
    LaunchedEffect(showExposure, exposureTouch) {
        if (showExposure) { delay(4000); showExposure = false }
    }

    fun meter(at: Offset, lock: Boolean) {
        val cam = camera ?: return
        val request = surfaceRequest ?: return
        val surface = with(transformer) { at.transform() }
        val w = request.resolution.width.toFloat()
        val h = request.resolution.height.toFloat()
        val point = SurfaceOrientedMeteringPointFactory(w, h).createPoint(surface.x.coerceIn(0f, w), surface.y.coerceIn(0f, h))
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).apply { if (lock) disableAutoCancel() }.build()
        // Fixed-focus lenses and devices without metering regions: nothing to do.
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return
        val mark = FocusMark(at, lock, System.nanoTime())
        focus = mark
        val result = cam.cameraControl.startFocusAndMetering(action)
        result.addListener({
            val ok = runCatching { result.get().isFocusSuccessful }.getOrDefault(false)
            if (focus?.id == mark.id) focus = mark.copy(focused = ok)
        }, ContextCompat.getMainExecutor(context))
        if (exposure != null) { showExposure = true; exposureTouch++ }
    }

    fun unlock() {
        camera?.cameraControl?.cancelFocusAndMetering()
        focus = null
    }

    fun shoot() {
        if (capturing) return
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
                    error = exception.message ?: exception.imageCaptureError.toString()
                }
            },
        )
    }

    // Turned on its side, the shutter, the torch and the gallery stand in a column at the right
    // edge, beside the frame, as in a camera app; upright they are in a row under it.
    val turned = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize()) {
        surfaceRequest?.let { request ->
            // The frame's shape as it appears on screen: the buffer is in sensor
            // orientation (landscape), so a portrait screen sees it the other way round.
            val portrait = !turned
            val long = maxOf(request.resolution.width, request.resolution.height).toFloat()
            val short = minOf(request.resolution.width, request.resolution.height).toFloat()
            val frame = if (portrait) short / long else long / short
            // Fitted, not cropped to the screen: a cropped viewfinder looked closer than the photo it took.
            // Everything drawn at a place in the picture lives in this box, in its coordinates.
            Box(Modifier.align(Alignment.Center).aspectRatio(frame, matchHeightConstraintsFirst = !portrait)) {
            CameraXViewfinder(
                surfaceRequest = request,
                coordinateTransformer = transformer,
                modifier = Modifier.fillMaxSize().pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val state = camera?.cameraInfo?.zoomState?.value ?: return@detectTransformGestures
                        val next = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        camera?.cameraControl?.setZoomRatio(next)
                    }
                }.pointerInput(camera, request) {
                    // Tap: focus and meter there, then back to continuous AF. Hold: lock AF/AE there.
                    detectTapGestures(
                        onTap = { meter(it, lock = false) },
                        onLongPress = { meter(it, lock = true) },
                    )
                },
            )
            codeBox?.let { CodeFrame(it) }
            focus?.let { mark -> FocusRing(mark, onFaded = { if (focus?.id == mark.id) focus = null }) }
            }
        }

        if (focus?.locked == true) {
            Surface(
                onClick = ::unlock,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = stringResource(Res.string.camera_focus_unlock), modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.camera_focus_locked), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        // Bottom right, mirroring the gallery button: both within the thumb's reach beside the shutter.
        IconButton(
            onClick = { torch = !torch },
            modifier = (
                if (turned) {
                    Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = SIDE_BUTTON_END).offset(y = -SIDE_BUTTON_OFFSET)
                } else {
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 32.dp, bottom = 44.dp)
                }
            ).size(56.dp).background(Color.White.copy(alpha = if (torch) 0.9f else 0.18f), CircleShape),
        ) {
            Icon(
                if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = stringResource(Res.string.camera_flash),
                tint = if (torch) Color.Black else Color.White,
            )
        }

        if (turned) {
            Box(Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 24.dp)) {
                Shutter(busy = capturing, onClick = ::shoot)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = if (turned) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedVisibility(code != null, enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 }, exit = fadeOut(tween(90))) {
                code?.let { CodeCard(it, services, onDismiss = { code = null }) }
            }
            if (exposure != null) {
                val range = exposure.exposureCompensationRange
                val step = exposure.exposureCompensationStep.toFloat()
                AnimatedVisibility(showExposure, enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 }, exit = fadeOut(tween(120))) {
                    ExposureSlider(
                        index = evIndex,
                        min = range.lower,
                        max = range.upper,
                        onChange = { evIndex = it; exposureTouch++ },
                        onReset = { evIndex = 0; screenMode = false; exposureTouch++ },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Screens are bright, emissive and flat: pull exposure down so the glyphs keep their
                    // edges, and drop any tap lock so continuous AF follows the user's hand.
                    CameraChip(
                        active = screenMode,
                        onClick = {
                            screenMode = !screenMode
                            evIndex = if (screenMode) screenIndex(step, range.lower, range.upper) else 0
                            unlock()
                        },
                    ) {
                        Icon(Icons.Rounded.Monitor, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.camera_screen_mode), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                    val evLabel = stringResource(Res.string.camera_exposure_value, formatEv(evIndex * step))
                    val exposureLabel = stringResource(Res.string.camera_exposure)
                    CameraChip(
                        active = evIndex != 0,
                        onClick = { showExposure = !showExposure; exposureTouch++ },
                        modifier = Modifier.semantics { contentDescription = "$exposureLabel $evLabel" },
                    ) {
                        Icon(Icons.Rounded.Exposure, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(evLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            Text(
                error?.let { stringResource(Res.string.camera_capture_failed, it) }
                    ?: if (screenMode) stringResource(Res.string.camera_screen_mode_on) else stringResource(Res.string.camera_hint),
                color = if (error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
            )
            if (!turned) Shutter(busy = capturing, onClick = ::shoot)
        }
    }
}

/** The side column of a turned screen: the small buttons centred on the shutter's axis, above and below it. */
private val SIDE_BUTTON_END = 38.dp
private val SIDE_BUTTON_OFFSET = 104.dp

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(Res.string.camera_shoot)
    Box(
        Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
            .background(if (busy) Color.White.copy(alpha = 0.5f) else Color.White, CircleShape)
            .clickable(enabled = !busy, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (busy) LoadingIndicator(Modifier.size(36.dp)) }
}

/** Where the user metered last: a tap (fades away) or a hold (stays, AF/AE locked). */
private data class FocusMark(val at: Offset, val locked: Boolean, val id: Long, val focused: Boolean? = null)

/** About −1.3 EV for screens, snapped to the device's step and clamped to its range. */
private fun screenIndex(step: Float, min: Int, max: Int): Int =
    if (step <= 0f) 0 else (-1.3f / step).roundToInt().coerceIn(min, max)

private fun formatEv(ev: Float): String =
    if (kotlin.math.abs(ev) < 0.05f) "0" else String.format(Locale.getDefault(), "%+.1f", ev)

private val FocusYellow = Color(0xFFFFD60A)

/** A code's corner points in the frame (fractions, upright) and when it was last seen there. */
private class CodeBox(val corners: List<Offset>, val seenAt: Long)

/** How long the frame stays on a code after the last sighting: analysis runs about 10 times a second. */
private const val CODE_BOX_LINGER_MS = 500L

/**
 * Brackets around a code the analyzer found, the way Lens marks one: the
 * bounding box of ZXing's points, padded, since a QR code's points are the
 * centres of its finder squares and a barcode's two ends of its scan line.
 */
@Composable
private fun CodeFrame(box: CodeBox) {
    if (box.corners.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val xs = box.corners.map { it.x * size.width }
        val ys = box.corners.map { it.y * size.height }
        var left = xs.min()
        var right = xs.max()
        var top = ys.min()
        var bottom = ys.max()
        val w = right - left
        val h = bottom - top
        // A 1D code gives a line: give it some height. A QR's finder centres sit inside its edge.
        val padX = max(w * 0.18f, 12.dp.toPx())
        val padY = if (h < w * 0.2f) max(w * 0.25f, 16.dp.toPx()) else max(h * 0.18f, 12.dp.toPx())
        left -= padX; right += padX; top -= padY; bottom += padY
        val arm = min(right - left, bottom - top) * 0.25f
        val stroke = 3.dp.toPx()
        val c = FocusYellow
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke, cap = StrokeCap.Round)
            drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke, cap = StrokeCap.Round)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
    }
}

@Composable
private fun FocusRing(mark: FocusMark, onFaded: () -> Unit) {
    val scale = remember(mark.id) { Animatable(1.35f) }
    val alpha = remember(mark.id) { Animatable(1f) }
    LaunchedEffect(mark.id) {
        scale.animateTo(1f, tween(180))
        if (!mark.locked) {
            delay(1100)
            alpha.animateTo(0f, tween(250))
            onFaded()
        }
    }
    val size = 72.dp
    val half = with(LocalDensity.current) { (size / 2).roundToPx() }
    val color = if (mark.locked || mark.focused == true) FocusYellow else Color.White
    Box(
        Modifier.offset { IntOffset(mark.at.x.roundToInt() - half, mark.at.y.roundToInt() - half) }
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color, radius = this.size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        if (mark.locked) Icon(Icons.Rounded.Lock, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        else Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

/** Small translucent pill for the camera's toggles, matching the round gallery/flash buttons. */
@Composable
private fun CameraChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (active) 0.9f else 0.18f),
        contentColor = if (active) Color.Black else Color.White,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun ExposureSlider(index: Int, min: Int, max: Int, onChange: (Int) -> Unit, onReset: () -> Unit) {
    val label = stringResource(Res.string.camera_exposure)
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Exposure, contentDescription = null, modifier = Modifier.size(18.dp))
            Slider(
                value = index.toFloat(),
                onValueChange = { v -> v.roundToInt().let { if (it != index) onChange(it) } },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.width(220.dp).padding(horizontal = 12.dp).semantics { contentDescription = label },
            )
            IconButton(onClick = onReset) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(Res.string.camera_exposure_reset))
            }
        }
    }
}

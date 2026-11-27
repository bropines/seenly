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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.runtime.mutableLongStateOf
import androidx.camera.core.ImageProxy
import android.os.SystemClock
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import android.hardware.display.DisplayManager
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
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
    light: Boolean,
) {
    val palette = if (light) lightCamera(MaterialTheme.colorScheme) else DarkCamera
    // The bars' icons dark on a light camera, light on a black one, while it is open; the theme's after.
    DisposableEffect(palette.light) {
        io.github.bropines.lens.app.platform.SystemBarsOverride.lightIcons = palette.light
        onDispose { io.github.bropines.lens.app.platform.SystemBarsOverride.lightIcons = null }
    }
    CompositionLocalProvider(LocalCameraPalette provides palette) {
        CameraContent(services, onCaptured, onPickImages, onBack)
    }
}

@Composable
private fun CameraContent(
    services: PlatformServices,
    onCaptured: (PlatformFile) -> Unit,
    onPickImages: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalCameraPalette.current
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (granted) {
            LiveCamera(services, onCaptured)
        } else if (asked) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.camera_permission), color = palette.content)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(Res.string.camera_allow))
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.back), tint = palette.content) }
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
            ).size(56.dp).background(palette.content.copy(alpha = palette.idle), CircleShape),
        ) { Icon(Icons.Rounded.PhotoLibrary, contentDescription = stringResource(Res.string.camera_from_gallery), tint = palette.content) }
    }
}

@Composable
private fun LiveCamera(services: PlatformServices, onCaptured: (PlatformFile) -> Unit) {
    val palette = LocalCameraPalette.current
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

    val preview = remember {
        Preview.Builder().setResolutionSelector(fourByThree).build().apply { setSurfaceProvider { surfaceRequest = it } }
    }
    val analysis = remember {
        // 4:3 like the preview, so a code's place in the frame is its place in the
        // viewfinder; and larger than the 640×480 default, or small codes are a few pixels.
        val analysisSize = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSize)
            .build()
    }
    // The activity is not recreated when the phone turns (it handles the change itself), so the
    // camera keeps running through the system's rotation animation; it is only told which way is
    // up now, for the viewfinder, the photo's orientation and where codes are in the frame.
    // A display listener, not the configuration: a half turn (landscape to the other landscape)
    // changes no configuration.
    val view = LocalView.current
    DisposableEffect(view) {
        val displays = context.getSystemService(DisplayManager::class.java)
        fun follow() {
            val rotation = view.display?.rotation ?: return
            preview.targetRotation = rotation
            capture.targetRotation = rotation
            analysis.targetRotation = rotation
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { if (displayId == view.display?.displayId) follow() }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        follow()
        displays.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }

    // Standby: a phone left lying still, or with nothing to see (face down, in a pocket), lets the
    // camera go instead of running it for nothing; moving the phone or touching the screen wakes it.
    // Stillness rather than lying flat: a phone held over a document on a table is flat too, but a
    // hand is never perfectly still.
    var standby by remember { mutableStateOf(false) }
    var stillSince by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var darkSince by remember { mutableLongStateOf(0L) }
    var graceUntil by remember { mutableLongStateOf(0L) }
    fun wake() {
        val now = SystemClock.uptimeMillis()
        standby = false
        stillSince = now
        darkSince = 0L
        graceUntil = now + STANDBY_GRACE_MS
    }
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val last = FloatArray(3)
        var primed = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                // How much the phone is moving: turning speed (rad/s), or without a gyroscope the change
                // in acceleration (m/s²) since the last reading.
                val motion = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                } else {
                    val d = if (primed) kotlin.math.abs(v[0] - last[0]) + kotlin.math.abs(v[1] - last[1]) + kotlin.math.abs(v[2] - last[2]) else 0f
                    v.copyInto(last, endIndex = 3)
                    primed = true
                    d / 4f
                }
                if (motion > STILL_MOTION) stillSince = SystemClock.uptimeMillis()
                if (standby && motion > WAKE_MOTION) wake()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        (gyro ?: accel)?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose {
            sensors?.unregisterListener(listener)
            executor.shutdown()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val now = SystemClock.uptimeMillis()
            if (standby || now < graceUntil) continue
            val still = now - stillSince > STILL_STANDBY_MS
            val dark = darkSince != 0L && now - darkSince > DARK_STANDBY_MS
            if (still || dark) standby = true
        }
    }

    LaunchedEffect(lifecycleOwner, standby) {
        if (standby) {
            camera = null
            code = null
            codeBox = null
            return@LaunchedEffect
        }
        analysis.setAnalyzer(executor, DarkProbe(
            CodeAnalyzer { found, corners ->
                // Called on the analysis thread; state is read by the UI.
                ContextCompat.getMainExecutor(context).execute {
                    if (found != code) code = found
                    codeBox = CodeBox(corners, System.nanoTime())
                }
            },
            onLuma = { luma ->
                ContextCompat.getMainExecutor(context).execute {
                    darkSince = if (luma < DARK_LUMA) darkSince.takeIf { it != 0L } ?: SystemClock.uptimeMillis() else 0L
                }
            },
        ))
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
            analysis.clearAnalyzer()
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
        // Asleep, the shutter wakes the camera first: there is no picture to take yet.
        if (standby) { wake(); return }
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
                // A texture, not a surface of its own: it fades and scales with the screen when the
                // camera opens and closes, where a SurfaceView stayed a black block to the end.
                implementationMode = ImplementationMode.EMBEDDED,
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

        // Asleep: the frame covered, a word on why, and a touch anywhere (or a move) wakes it.
        androidx.compose.animation.AnimatedVisibility(standby, enter = fadeIn(tween(200)), exit = fadeOut(tween(150)), modifier = Modifier.fillMaxSize()) {
            val palette = LocalCameraPalette.current
            Box(
                Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.92f))
                    .clickable(interactionSource = null, indication = null, onClick = ::wake),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = palette.content, modifier = Modifier.size(40.dp))
                    Text(stringResource(Res.string.camera_standby), color = palette.content, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(Res.string.camera_standby_hint), color = palette.content.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
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
            ).size(56.dp).background(palette.content.copy(alpha = if (torch) 0.9f else palette.idle), CircleShape),
        ) {
            Icon(
                if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = stringResource(Res.string.camera_flash),
                tint = if (torch) palette.background else palette.content,
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
                // Turned, the hint lies over the picture; upright, on the screen below it.
                color = if (error != null) MaterialTheme.colorScheme.error else (if (turned) Color.White else palette.content).copy(alpha = 0.8f),
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
        Modifier.size(84.dp).border(4.dp, LocalCameraPalette.current.content, CircleShape).padding(8.dp)
            .background(LocalCameraPalette.current.content.copy(alpha = if (busy) 0.5f else 1f), CircleShape)
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

/** Standby: how long still or dark before the camera sleeps, and the motions that count. */
private const val STILL_STANDBY_MS = 5_000L
private const val DARK_STANDBY_MS = 3_000L
/** After waking, a moment before it may sleep again: the phone may still be on the table. */
private const val STANDBY_GRACE_MS = 4_000L
/** Turning faster than this (rad/s) is a hand holding the phone; [WAKE_MOTION] is picking it up. */
private const val STILL_MOTION = 0.05f
private const val WAKE_MOTION = 0.3f
/** Average brightness (0-255) below which the frame shows nothing: a lens on the table or in a pocket. */
private const val DARK_LUMA = 14

/**
 * Passes frames on to [next] and, twice a second, tells [onLuma] how bright the frame is: the
 * average of a sparse grid of the luma plane, read without moving the buffer [next] reads.
 */
private class DarkProbe(private val next: ImageAnalysis.Analyzer, private val onLuma: (Int) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - last >= 500) {
            last = now
            runCatching {
                val plane = image.planes[0]
                val buffer = plane.buffer
                var sum = 0L
                var count = 0
                for (y in 0 until image.height step 16) {
                    for (x in 0 until image.width step 16) {
                        sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                        count++
                    }
                }
                if (count > 0) onLuma((sum / count).toInt())
            }
        }
        next.analyze(image)
    }
}

/**
 * The camera screen's colours around the frame: black with white controls, or (the "Light camera"
 * setting) the light theme's background with dark ones. What is drawn over the picture itself (the
 * focus ring, the exposure slider, the code frame) keeps its own colours, as it sits on the photo.
 */
private data class CameraPalette(
    val background: Color,
    val content: Color,
    /** How strongly an idle round button is filled with [content]. */
    val idle: Float,
    /** Light: the status bar's icons go dark. */
    val light: Boolean,
)

private val DarkCamera = CameraPalette(Color.Black, Color.White, idle = 0.18f, light = false)

/** The app's own light background when its theme is light; a neutral light one under a dark theme. */
private fun lightCamera(scheme: androidx.compose.material3.ColorScheme): CameraPalette =
    if (scheme.background.luminance() > 0.5f) {
        CameraPalette(scheme.background, scheme.onBackground, idle = 0.1f, light = true)
    } else {
        CameraPalette(Color(0xFFF4F4F4), Color(0xFF1B1B1B), idle = 0.1f, light = true)
    }

private val LocalCameraPalette = staticCompositionLocalOf { DarkCamera }

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
        // Over the picture: a veil in the camera's own tone, dark or light, so the chip reads on any photo.
        color = LocalCameraPalette.current.let { p ->
            if (active) p.content.copy(alpha = 0.9f) else p.background.copy(alpha = if (p.light) 0.85f else 0.45f)
        },
        contentColor = LocalCameraPalette.current.let { p -> if (active) p.background else p.content },
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

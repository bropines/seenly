package io.github.bropines.lens.app.ui.camera

import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.vinceglb.filekit.PlatformFile

actual val cameraAvailable: Boolean = false

/** The desktop has no camera flow; offer the file picker instead. */
@Composable
actual fun CameraScreen(
    services: PlatformServices,
    onCaptured: (PlatformFile) -> Unit,
    onPickImages: () -> Unit,
    onBack: () -> Unit,
    light: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.camera_desktop))
        Button(onClick = onPickImages) { Text(stringResource(Res.string.open_images)) }
        Button(onClick = onBack) { Text(stringResource(Res.string.back)) }
    }
}

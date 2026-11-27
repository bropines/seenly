package io.github.bropines.lens.app.ui.camera

import androidx.compose.runtime.Composable
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.vinceglb.filekit.PlatformFile

/** Whether this platform has a camera screen (the desktop does not). */
expect val cameraAvailable: Boolean

/** A code the camera recognised: its text and format (QR_CODE, EAN_13, ...). */
data class ScannedCode(val text: String, val format: String) {
    val isUrl: Boolean get() = text.startsWith("http://", true) || text.startsWith("https://", true)

    /** Wi-Fi QR codes: `WIFI:T:WPA;S:name;P:password;;`. */
    val wifi: Pair<String, String?>? get() {
        if (!text.startsWith("WIFI:", true)) return null
        fun field(key: String) = Regex("""(?:^|;)$key:((?:\\.|[^;])*)""").find(text.substring(5))?.groupValues?.get(1)
            ?.replace(Regex("""\\(.)"""), "$1")
        val ssid = field("S") ?: return null
        return ssid to field("P")
    }
}

/**
 * Live camera, the way Lens works: point, tap the shutter, get the picture
 * translated. Codes in view are read continuously and offered as a chip.
 */
@Composable
expect fun CameraScreen(
    services: PlatformServices,
    onCaptured: (PlatformFile) -> Unit,
    onPickImages: () -> Unit,
    onBack: () -> Unit,
    /** Light around the frame with dark controls (the "Light camera" setting), else black. */
    light: Boolean = false,
)

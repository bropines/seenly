package io.github.bropines.lens.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext

actual fun androidx.compose.ui.graphics.ImageBitmap.encodePng(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

/**
 * A screen that sets the bars' icons for itself while it is open (the camera, whose background is
 * not the theme's): true for dark icons, false for light ones, null to follow the theme.
 */
object SystemBarsOverride {
    internal var lightIcons by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    private var generation by androidx.compose.runtime.mutableIntStateOf(0)
    internal val refreshes: Int get() = generation

    /** Sets the bars again: something outside Compose (the splash screen leaving) has reset them. */
    fun refresh() { generation++ }
}

@androidx.compose.runtime.Composable
actual fun PlatformSystemBars(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val light = SystemBarsOverride.lightIcons ?: !dark
    // Again after every configuration change too (language, dark mode, turning): the window
    // resets the bars' appearance from its theme then.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // Read so that a refresh() recomposes this and sets the bars again.
    @Suppress("UNUSED_VARIABLE") val refreshes = SystemBarsOverride.refreshes
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            // Always set: enableEdgeToEdge() sets them by the system's dark mode through the platform
            // controller, and reading them back through this one can tell the wrong story.
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
    androidx.compose.runtime.LaunchedEffect(configuration) {
        // The reset can come after this frame; once more a moment later.
        kotlinx.coroutines.delay(100)
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

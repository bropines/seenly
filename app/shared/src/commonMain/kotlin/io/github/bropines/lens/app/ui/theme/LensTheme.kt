package io.github.bropines.lens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.platform.platformDynamicColorScheme

/** Lens blue-violet, used to seed the palette where there is no wallpaper colour. */
private val Seed = Color(0xFF5B5BD6)

@Composable
fun LensTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = platformDynamicColorScheme(dark)
        ?: rememberDynamicColorScheme(seedColor = Seed, isDark = dark, style = PaletteStyle.Expressive)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

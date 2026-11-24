package io.github.bropines.lens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.platform.platformDynamicColorScheme

/** Lens blue-violet, used to seed the palette where there is no wallpaper colour. */
private val Seed = Color(0xFF5B5BD6)

/**
 * Whether the wallpaper's colours may replace the seed palette. Always true in
 * the app; the README screenshots turn it off to show Seenly's own colours.
 */
val LocalDynamicColor = staticCompositionLocalOf { true }

@Composable
fun LensTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val dynamic = if (LocalDynamicColor.current) platformDynamicColorScheme(dark) else null
    val scheme = dynamic
        ?: rememberDynamicColorScheme(seedColor = Seed, isDark = dark, style = PaletteStyle.Expressive)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        // Standard, not expressive: the expressive springs overshoot and read as slow here.
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}

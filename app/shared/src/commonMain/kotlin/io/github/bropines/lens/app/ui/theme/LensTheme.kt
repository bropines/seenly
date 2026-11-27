package io.github.bropines.lens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import io.github.bropines.lens.app.data.ThemeColor
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.platform.platformDynamicColorScheme
import io.github.bropines.lens.app.platform.PlatformSystemBars
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

/** Lens blue-violet, used to seed the palette where there is no wallpaper colour. */
private val Seed = Color(0xFF5B5BD6)

/** The colour each preset seeds its palette with; Mono's is only the tint of its greys. */
fun ThemeColor.seed(): Color = when (this) {
    ThemeColor.Wallpaper, ThemeColor.Violet -> Seed
    ThemeColor.Blue -> Color(0xFF1E6FD9)
    ThemeColor.Teal -> Color(0xFF00897B)
    ThemeColor.Green -> Color(0xFF3B8F3A)
    ThemeColor.Amber -> Color(0xFFE08A00)
    ThemeColor.Red -> Color(0xFFD13438)
    ThemeColor.Pink -> Color(0xFFD63B83)
    ThemeColor.Mono -> Color(0xFF6F6F6F)
}

/**
 * Whether the wallpaper's colours may replace the seed palette. Always true in
 * the app; the README screenshots turn it off to show Seenly's own colours.
 */
val LocalDynamicColor = staticCompositionLocalOf { true }

@Composable
fun LensTheme(
    mode: ThemeMode,
    color: ThemeColor = ThemeColor.Wallpaper,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val wallpaper = if (color == ThemeColor.Wallpaper && LocalDynamicColor.current) platformDynamicColorScheme(dark) else null
    val seeded = rememberDynamicColorScheme(
        seedColor = color.seed(),
        isDark = dark,
        style = if (color == ThemeColor.Mono) PaletteStyle.Monochrome else PaletteStyle.Expressive,
    )
    val base = wallpaper ?: seeded
    val scheme = if (dark && amoled) remember(base) { base.pureBlack() } else base
    PlatformSystemBars(dark)
    MaterialExpressiveTheme(
        // A new theme or colour flows in instead of snapping.
        colorScheme = animated(scheme),
        // Standard, not expressive: the expressive springs overshoot and read as slow here.
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}

/** [target] with its colours eased towards each new value: switching the theme or its colour fades across. */
@Composable
private fun animated(target: ColorScheme): ColorScheme {
    @Composable
    fun Color.eased(): Color = animateColorAsState(this, tween(THEME_FADE_MS), label = "theme").value
    return target.copy(
        primary = target.primary.eased(),
        onPrimary = target.onPrimary.eased(),
        primaryContainer = target.primaryContainer.eased(),
        onPrimaryContainer = target.onPrimaryContainer.eased(),
        secondary = target.secondary.eased(),
        onSecondary = target.onSecondary.eased(),
        secondaryContainer = target.secondaryContainer.eased(),
        onSecondaryContainer = target.onSecondaryContainer.eased(),
        tertiary = target.tertiary.eased(),
        onTertiary = target.onTertiary.eased(),
        tertiaryContainer = target.tertiaryContainer.eased(),
        onTertiaryContainer = target.onTertiaryContainer.eased(),
        background = target.background.eased(),
        onBackground = target.onBackground.eased(),
        surface = target.surface.eased(),
        onSurface = target.onSurface.eased(),
        surfaceVariant = target.surfaceVariant.eased(),
        onSurfaceVariant = target.onSurfaceVariant.eased(),
        surfaceContainerLowest = target.surfaceContainerLowest.eased(),
        surfaceContainerLow = target.surfaceContainerLow.eased(),
        surfaceContainer = target.surfaceContainer.eased(),
        surfaceContainerHigh = target.surfaceContainerHigh.eased(),
        surfaceContainerHighest = target.surfaceContainerHighest.eased(),
        outline = target.outline.eased(),
        outlineVariant = target.outlineVariant.eased(),
    )
}

private const val THEME_FADE_MS = 300

/**
 * The dark scheme on true black: the background and the lowest surfaces turn
 * off the OLED pixels, and the raised containers step up in small, neutral
 * greys so cards and sheets still stand apart.
 */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF232327),
    surfaceBright = Color(0xFF2A2A2E),
)

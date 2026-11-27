package io.github.bropines.lens.app.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.encodePng(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("Skia could not encode the page as PNG")

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

@androidx.compose.runtime.Composable
actual fun PlatformSystemBars(dark: Boolean) = Unit

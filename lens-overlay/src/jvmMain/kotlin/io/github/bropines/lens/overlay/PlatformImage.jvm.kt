package io.github.bropines.lens.overlay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual fun decodeEncodedImage(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
} catch (e: Exception) {
    null
}

internal actual fun imageBitmapFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    require(width > 0 && height > 0 && pixels.size >= width * height) { "Bad pixel buffer for ${width}x$height" }
    // An ARGB Int laid out little-endian is B, G, R, A: Skia's BGRA_8888. Skia
    // draws premultiplied bitmaps, so the pixels are converted on the way in.
    val bytes = ByteArray(width * height * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(premultiply(pixels), 0, width * height)
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

internal actual fun ImageBitmap.forSoftwareCanvas(): ImageBitmap = this

package io.github.bropines.lens.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeEncodedImage(bytes: ByteArray): ImageBitmap? = try {
    // Software, not hardware: the pixels are read back to merge alpha masks.
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
} catch (e: Exception) {
    null
}

internal actual fun imageBitmapFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    require(width > 0 && height > 0 && pixels.size >= width * height) { "Bad pixel buffer for ${width}x$height" }
    // createBitmap takes non-premultiplied colours and premultiplies them itself.
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal actual fun ImageBitmap.forSoftwareCanvas(): ImageBitmap {
    val bitmap = asAndroidBitmap()
    if (bitmap.config != Bitmap.Config.HARDWARE) return this
    return bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
}

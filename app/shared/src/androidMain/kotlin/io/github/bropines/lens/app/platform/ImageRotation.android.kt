package io.github.bropines.lens.app.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

actual fun rotateEncodedImage(bytes: ByteArray, turns: Int): ByteArray {
    val q = quarterTurns(turns)
    if (q == 0) return bytes
    // Decoded as lens-protocol does it, EXIF orientation applied, so the turn is on top of what is shown.
    val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("could not decode the image to rotate it")
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f * q) }, false)
    if (rotated !== source) source.recycle()
    val out = ByteArrayOutputStream()
    if (rotated.hasAlpha()) {
        rotated.compress(Bitmap.CompressFormat.PNG, 100, out)
    } else {
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    rotated.recycle()
    return out.toByteArray()
}

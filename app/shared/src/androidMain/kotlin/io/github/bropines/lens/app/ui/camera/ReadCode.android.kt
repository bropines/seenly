package io.github.bropines.lens.app.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Looks for a code in a whole picture with ZXing, as the camera does in its
 * frames: first at up to [LARGE] px, where small codes survive, then at
 * [SMALL] px, where a big one photographed slightly blurred reads better.
 */
actual suspend fun readCode(image: ByteArray): ScannedCode? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    for (side in intArrayOf(LARGE, SMALL)) {
        coroutineContext.ensureActive()
        val bitmap = decode(image, bounds.outWidth, bounds.outHeight, side) ?: continue
        val code = try {
            read(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (code != null) return@withContext code
        // A picture already smaller than the second size was just read whole.
        if (maxOf(bounds.outWidth, bounds.outHeight) <= SMALL) break
    }
    null
}

private fun decode(image: ByteArray, width: Int, height: Int, side: Int): Bitmap? {
    var sample = 1
    // Rounded up: never decode bigger than needed (a 12 MP photo at full size is ~48 MB).
    while (maxOf(width, height) / sample > side) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= side) return decoded
    val scale = side.toFloat() / longest
    return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        .also { if (it !== decoded) decoded.recycle() }
}

private fun read(bitmap: Bitmap): ScannedCode? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.ALSO_INVERTED to true))
    }
    return try {
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
        result.text?.takeIf { it.isNotBlank() }?.let { ScannedCode(it, result.barcodeFormat.name) }
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        // A malformed code (checksum, format) is as good as none.
        null
    }
}

private const val LARGE = 2048
private const val SMALL = 800

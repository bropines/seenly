package io.github.bropines.lens.app.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.geometry.Offset
import io.github.bropines.wechatqr.WeChatQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

/**
 * Looks for a QR code in a whole picture with WeChat's decoder (module :wechat-qr): it reads codes
 * that Java ZXing, zxing-cpp, BoofCV and OpenCV's own detector all miss, such as one photographed
 * off a monitor with its moiré, and gives the code's four corners for the frame drawn around it.
 */
actual suspend fun readCode(image: ByteArray): CodeHit? = withContext(Dispatchers.Default) {
    // The decoder scales by itself (halves and doubles); a second, smaller size helps a big code
    // whose moiré averages out when shrunk.
    for (side in SIDES) {
        coroutineContext.ensureActive()
        val bitmap = decodeUpright(image, side) ?: return@withContext null
        try {
            val corners = FloatArray(8 * MAX_CODES)
            val found = runCatching { WeChatQr.decode(bitmap, corners) }
                .onFailure { android.util.Log.w("Codes", "WeChat QR failed on ${bitmap.width}x${bitmap.height}", it) }
                .getOrNull()
                .orEmpty()
            // What was found, never what it says.
            android.util.Log.d("Codes", "WeChat QR: ${found.size} code(s) in ${bitmap.width}x${bitmap.height}")
            val index = found.indexOfFirst { it.isNotEmpty() }.takeIf { it in 0 until MAX_CODES } ?: continue
            val text = decodeText(found[index]).takeIf { it.isNotBlank() } ?: continue
            val points = (0 until 4).map { corner ->
                Offset(corners[index * 8 + corner * 2] / bitmap.width, corners[index * 8 + corner * 2 + 1] / bitmap.height)
            }
            return@withContext CodeHit(ScannedCode(text, "QR_CODE"), points)
        } finally {
            bitmap.recycle()
        }
    }
    null
}

/** A code's bytes as text: UTF-8 when they are, else Latin-1, the QR standard's default. */
private fun decodeText(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    String(bytes, Charsets.ISO_8859_1)
}

private const val MAX_CODES = 8

/** The sizes tried, in px on the long side. */
private val SIDES = intArrayOf(2048, 1024)

/**
 * [image] decoded no larger than [side] px, turned upright by its EXIF orientation, as the viewer
 * shows it; null when it is not a picture.
 */
fun decodeUpright(image: ByteArray, side: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Halved only while the result stays near [side]: a whole halving more can shrink a small code
    // past reading (2772 px would become 1386). The last step down is a smooth scale instead.
    var sample = 1
    while (side != Int.MAX_VALUE && maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side * 3 / 4) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(image)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > side) side.toFloat() / longest else 1f
    if (degrees == 0 && scale == 1f) return decoded
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
}

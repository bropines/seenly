package io.github.bropines.lens.app.ui.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Reads QR codes and barcodes from camera frames with ZXing, and says where
 * in the frame each one is.
 *
 * Works on the luminance (Y) plane only. QR is rotation-invariant, but 1D
 * barcodes are read along rows, and the sensor is landscape while the phone is
 * held upright, so every other frame is also tried rotated by 90 degrees.
 * Light-on-dark codes (common on screens in dark mode) are tried too.
 * Frames are throttled: decoding every one would heat the phone for nothing.
 *
 * [onCode] gets the code and its corner points as fractions of the frame as
 * it is shown upright (0..1 across and down), so they can be drawn over a
 * viewfinder showing the same frame.
 */
class CodeAnalyzer(private val onCode: (ScannedCode, List<Offset>) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF,
                ),
                // Slower per frame, but frames are throttled and small or skewed codes are found.
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }
    private var lastRun = 0L
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = System.currentTimeMillis()
            if (now - lastRun < INTERVAL_MS) return
            lastRun = now
            val plane = it.planes[0]
            val buffer = plane.buffer.also { b -> b.rewind() }
            val width = it.width
            val height = it.height
            val stride = plane.rowStride
            val luma = ByteArray(width * height)
            // Copy row by row: the row stride can be wider than the image.
            for (row in 0 until height) {
                buffer.position(row * stride)
                buffer.get(luma, row * width, width)
            }
            val rotate = frame++ % 2 == 1L
            val source = if (rotate) {
                PlanarYUVLuminanceSource(rotate90(luma, width, height), height, width, 0, 0, height, width, false)
            } else {
                PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
            }
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (_: NotFoundException) {
                null
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            result ?: return
            val degrees = it.imageInfo.rotationDegrees
            val corners = result.resultPoints.orEmpty().filterNotNull().map { p ->
                // Back to the sensor buffer's own coordinates first...
                val (x, y) = if (rotate) p.y to (height - 1 - p.x) else p.x to p.y
                // ...then turned the way the frame is shown.
                upright(x / width, y / height, degrees)
            }
            onCode(ScannedCode(result.text, result.barcodeFormat.name), corners)
        }
    }

    /** A point of the buffer, as fractions of it, where it appears once the frame is turned by [degrees]. */
    private fun upright(nx: Float, ny: Float, degrees: Int): Offset = when ((degrees % 360 + 360) % 360) {
        90 -> Offset(1f - ny, nx)
        180 -> Offset(1f - nx, 1f - ny)
        270 -> Offset(ny, 1f - nx)
        else -> Offset(nx, ny)
    }

    /** The plane turned a quarter clockwise: output row r, column c is input (x = r, y = height - 1 - c). */
    private fun rotate90(src: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(src.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) out[i++] = src[y * width + x]
        }
        return out
    }

    private companion object {
        // About 10 tries a second: a code is framed almost as soon as it enters the view,
        // and the frame follows it, while a busy decode simply drops the frames between.
        const val INTERVAL_MS = 100L
    }
}

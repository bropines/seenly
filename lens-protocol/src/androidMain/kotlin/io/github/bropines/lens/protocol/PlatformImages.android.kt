package io.github.bropines.lens.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal actual object PlatformImages {
    actual fun decode(bytes: ByteArray): Raster {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation; a software bitmap so the pixels can be read.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw LensException.UnreadableImage("BitmapFactory could not decode it")
        }
        return bitmap.toRaster().also { bitmap.recycle() }
    }

    actual fun scale(raster: Raster, width: Int, height: Int): Raster {
        // Bilinear aliases on a big downscale, so halve first while more than 2x away.
        var bitmap = raster.toBitmap()
        while (bitmap.width / 2 >= width && bitmap.height / 2 >= height) {
            bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true).also { bitmap.recycle() }
        }
        val out = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (out !== bitmap) bitmap.recycle()
        return out.toRaster().also { out.recycle() }
    }

    actual fun encodeJpeg(raster: Raster, quality: Int): ByteArray {
        val bitmap = raster.toBitmap()
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    private fun Bitmap.toRaster(): Raster {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

    private fun Raster.toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}

package io.github.bropines.lens.protocol

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal actual object PlatformImages {
    actual fun decode(bytes: ByteArray): Raster {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw LensException.UnreadableImage("no ImageIO reader for this format")
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Raster(image.width, image.height, argb)
    }

    actual fun scale(raster: Raster, width: Int, height: Int): Raster {
        // Area averaging is slow but has none of bilinear's aliasing on a big downscale.
        val scaled = toImage(raster, BufferedImage.TYPE_INT_ARGB).getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(scaled, 0, 0, null); dispose() }
        return Raster(width, height, out.getRGB(0, 0, width, height, null, 0, width))
    }

    actual fun encodeJpeg(raster: Raster, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(toImage(raster, BufferedImage.TYPE_INT_RGB), null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private fun toImage(raster: Raster, type: Int) = BufferedImage(raster.width, raster.height, type).apply {
        setRGB(0, 0, raster.width, raster.height, raster.argb, 0, raster.width)
    }
}

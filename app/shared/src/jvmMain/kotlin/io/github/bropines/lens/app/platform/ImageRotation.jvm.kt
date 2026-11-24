package io.github.bropines.lens.app.platform

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

actual fun rotateEncodedImage(bytes: ByteArray, turns: Int): ByteArray {
    val q = quarterTurns(turns)
    if (q == 0) return bytes
    // ImageIO, as lens-protocol decodes it on desktop.
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("no ImageIO reader for this image")
    val w = image.width
    val h = image.height
    val pixels = rotateArgb(image.getRGB(0, 0, w, h, null, 0, w), w, h, q)
    val rw = if (q % 2 == 1) h else w
    val rh = if (q % 2 == 1) w else h
    val alpha = image.colorModel.hasAlpha()
    val rotated = BufferedImage(rw, rh, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    rotated.setRGB(0, 0, rw, rh, pixels, 0, rw)
    val out = ByteArrayOutputStream()
    if (alpha) {
        ImageIO.write(rotated, "png", out)
    } else {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.95f
                }
                writer.write(null, IIOImage(rotated, null, null), params)
            }
        } finally {
            writer.dispose()
        }
    }
    return out.toByteArray()
}

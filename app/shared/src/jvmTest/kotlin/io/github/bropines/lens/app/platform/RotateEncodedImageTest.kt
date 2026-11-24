package io.github.bropines.lens.app.platform

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RotateEncodedImageTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val clear = 0x00000000

    /** 4 wide, 2 high, transparent so it round-trips losslessly as PNG; red top-left pixel. */
    private fun png(): ByteArray {
        val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 2) for (x in 0 until 4) image.setRGB(x, y, clear)
        image.setRGB(0, 0, red)
        image.setRGB(3, 1, blue)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun quarterTurnSwapsTheSidesAndMovesTheCorners() {
        val out = ImageIO.read(ByteArrayInputStream(rotateEncodedImage(png(), 1)))
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        // Top-left goes to top-right, bottom-right to bottom-left.
        assertEquals(red, out.getRGB(1, 0))
        assertEquals(blue, out.getRGB(0, 3))
    }

    @Test
    fun opaquePicturesComeBackAsJpeg() {
        val image = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val out = rotateEncodedImage(jpeg, 3)
        // JPEG's SOI marker.
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        val decoded = ImageIO.read(ByteArrayInputStream(out))
        assertEquals(4, decoded.width)
        assertEquals(8, decoded.height)
    }

    @Test
    fun noTurnKeepsTheBytes() {
        val bytes = png()
        assertSame(bytes, rotateEncodedImage(bytes, 0))
        assertSame(bytes, rotateEncodedImage(bytes, 4))
    }
}

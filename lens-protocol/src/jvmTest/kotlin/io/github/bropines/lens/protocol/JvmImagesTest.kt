package io.github.bropines.lens.protocol

import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test

class JvmImagesTest {
    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { fillRect(width / 4, height / 4, width / 2, height / 2); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun bigImageIsDownscaledAndUploadedAsJpeg() {
        val prepared = ImagePreparer.prepare(png(4000, 2000))
        (prepared.width to prepared.height) shouldBe (1600 to 800)
        val decoded = ImageIO.read(ByteArrayInputStream(prepared.jpeg))
        (decoded.width to decoded.height) shouldBe (1600 to 800)
        // JPEG SOI marker.
        (prepared.jpeg[0] == 0xFF.toByte() && prepared.jpeg[1] == 0xD8.toByte()) shouldBe true
    }

    @Test
    fun screenshotSizedImageKeepsItsResolution() {
        val prepared = ImagePreparer.prepare(png(1600, 900))
        (prepared.width to prepared.height) shouldBe (1600 to 900)
    }
}

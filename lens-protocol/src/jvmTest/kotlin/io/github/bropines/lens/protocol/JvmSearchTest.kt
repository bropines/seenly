package io.github.bropines.lens.protocol

import io.kotest.matchers.shouldBe
import lens.StickinessSignals
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test

class JvmSearchTest {
    @Test
    fun formulaUrlCarriesTheEquationAsAGzippedStickinessSignal() {
        val url = LensSearchUrls.formula("x^2 = 4", text = "x² = 4")
        val parameters = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        (parameters - "stick") shouldBe mapOf("q" to "x%C2%B2%20%3D%204", "lns_fp" to "1", "lns_mode" to "text", "lns_surface" to "42")

        val gzipped = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parameters.getValue("stick"))
        val signals = StickinessSignals.ADAPTER.decode(GZIPInputStream(ByteArrayInputStream(gzipped)).readBytes())
        signals.id_namespace shouldBe StickinessSignals.Namespace.EDUCATION_INPUT
        signals.education_input_extension!!.math_solver_query!!.math_input_equation shouldBe "x^2 = 4"
    }

    @Test
    fun aRegionOfARealImageIsCutAndEncodedAsJpeg() {
        // Left half black, right half white.
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.WHITE; fillRect(100, 0, 100, 100); dispose() }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val raster = ImagePreparer.uploadRaster(ImagePreparer.decode(png), AlphaMode.Contrast)
        val crop = ImageCodec.Platform.encodeJpeg(raster.crop(Region(0.75f, 0f, 0.25f, 0.5f).toPixels(raster.width, raster.height)))
        (crop.width to crop.height) shouldBe (50 to 50)
        val decoded = ImageIO.read(ByteArrayInputStream(crop.jpeg))
        (decoded.width to decoded.height) shouldBe (50 to 50)
        // From the white half.
        (decoded.getRGB(25, 25) and 0xFF > 240) shouldBe true
    }
}

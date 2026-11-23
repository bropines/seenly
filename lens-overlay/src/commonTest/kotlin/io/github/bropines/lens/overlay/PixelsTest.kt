package io.github.bropines.lens.overlay

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PixelsTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val red = argb(0xFF, 0xFF, 0, 0)
    private val green = argb(0xFF, 0, 0xFF, 0)
    private val blue = argb(0xFF, 0, 0, 0xFF)

    // ---- colour choice -------------------------------------------------------

    @Test
    fun `text is black on light backgrounds and white on dark ones`() {
        contrastingTextColor(ARGB_WHITE) shouldBe ARGB_BLACK
        contrastingTextColor(argb(0xFF, 0xFF, 0xEE, 0x58)) shouldBe ARGB_BLACK // yellow
        contrastingTextColor(ARGB_BLACK) shouldBe ARGB_WHITE
        contrastingTextColor(argb(0xFF, 0x1A, 0x23, 0x7E)) shouldBe ARGB_WHITE // navy
        contrastingTextColor(red) shouldBe ARGB_BLACK
        contrastingTextColor(blue) shouldBe ARGB_WHITE
    }

    @Test
    fun `contrast ratio spans one to twenty one`() {
        contrastRatio(ARGB_BLACK, ARGB_WHITE) shouldBe (21.0 plusOrMinus 1e-9)
        contrastRatio(red, red) shouldBe 1.0
        contrastRatio(ARGB_WHITE, ARGB_BLACK) shouldBe contrastRatio(ARGB_BLACK, ARGB_WHITE)
    }

    @Test
    fun `the colour Lens sent wins over the contrast pick`() {
        resolveTextColor(red, ARGB_WHITE) shouldBe red
        resolveTextColor(null, ARGB_WHITE) shouldBe ARGB_BLACK
        resolveTextColor(null, ARGB_BLACK) shouldBe ARGB_WHITE
        // A fully transparent colour is as good as none.
        resolveTextColor(argb(0, 0xFF, 0, 0), ARGB_BLACK) shouldBe ARGB_WHITE
    }

    @Test
    fun `the median ignores outliers`() {
        val paper = argb(0xFF, 0xF0, 0xF0, 0xE8)
        medianColor(intArrayOf(paper, paper, paper, ARGB_BLACK, paper, red, paper)) shouldBe paper
        medianColor(intArrayOf()) shouldBe null
        // The result is opaque whatever the samples' alpha.
        medianColor(intArrayOf(argb(0x10, 1, 2, 3))) shouldBe argb(0xFF, 1, 2, 3)
    }

    // ---- raw patches ---------------------------------------------------------

    @Test
    fun `raw bytes are read as R G B A`() {
        val bytes = byteArrayOf(
            0x11, 0x22, 0x33, 0x44,
            0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(),
        )
        rgbaBytesToArgb(bytes, 2, 1)!!.toList() shouldBe listOf(argb(0x44, 0x11, 0x22, 0x33), argb(0xFF, 0xFF, 0x00, 0x80))
    }

    @Test
    fun `a raw buffer too short for its size is rejected`() {
        rgbaBytesToArgb(ByteArray(7), 2, 1) shouldBe null
        rgbaBytesToArgb(ByteArray(8), 0, 1) shouldBe null
        rgbaBytesToArgb(ByteArray(8), 2, -1) shouldBe null
    }

    // ---- JPEG + PNG mask -----------------------------------------------------

    @Test
    fun `the mask's alpha channel becomes the patch's alpha`() {
        val rgb = intArrayOf(red, green, blue, ARGB_WHITE)
        val mask = intArrayOf(argb(0, 0, 0, 0), argb(0x40, 9, 9, 9), argb(0xC0, 0, 0, 0), argb(0xFF, 0, 0, 0))
        mergeAlphaMask(rgb, 2, 2, mask, 2, 2).toList() shouldBe listOf(
            argb(0, 0xFF, 0, 0),
            argb(0x40, 0, 0xFF, 0),
            argb(0xC0, 0, 0, 0xFF),
            argb(0xFF, 0xFF, 0xFF, 0xFF),
        )
    }

    @Test
    fun `an opaque greyscale mask is read by brightness`() {
        val rgb = intArrayOf(red, green, blue)
        val mask = intArrayOf(ARGB_BLACK, argb(0xFF, 0x80, 0x80, 0x80), ARGB_WHITE)
        mergeAlphaMask(rgb, 3, 1, mask, 3, 1).toList() shouldBe listOf(
            argb(0, 0xFF, 0, 0),
            argb(0x80, 0, 0xFF, 0),
            argb(0xFF, 0, 0, 0xFF),
        )
    }

    @Test
    fun `colour never comes from the mask and alpha never from the jpeg`() {
        // A JPEG decodes opaque; whatever alpha its pixels carry is replaced.
        val rgb = intArrayOf(argb(0x12, 0xAA, 0xBB, 0xCC))
        val mask = intArrayOf(argb(0x7F, 0x01, 0x02, 0x03))
        mergeAlphaMask(rgb, 1, 1, mask, 1, 1).single() shouldBe argb(0x7F, 0xAA, 0xBB, 0xCC)
    }

    @Test
    fun `a mask of another size is stretched over the image`() {
        // 4x2 colour, 2x1 mask: the left half transparent, the right half opaque.
        val rgb = IntArray(8) { red }
        val mask = intArrayOf(argb(0, 0, 0, 0), argb(0xFE, 0, 0, 0))
        val merged = mergeAlphaMask(rgb, 4, 2, mask, 2, 1)
        merged.map { it ushr 24 } shouldBe listOf(0, 0, 0xFE, 0xFE, 0, 0, 0xFE, 0xFE)
    }

    @Test
    fun `premultiplying scales colour by alpha`() {
        premultiply(intArrayOf(argb(0x80, 0xFF, 0x40, 0x00), red, argb(0, 0xFF, 0xFF, 0xFF))).toList() shouldBe
            listOf(argb(0x80, 0x80, 0x20, 0x00), red, 0)
    }
}

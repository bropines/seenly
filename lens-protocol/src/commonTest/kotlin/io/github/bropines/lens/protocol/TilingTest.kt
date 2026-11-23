package io.github.bropines.lens.protocol

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TilingTest {
    @Test
    fun anImageThatFitsIsScannedWhole() {
        Tiling.bands(1600, 900) shouldBe null
        Tiling.bands(2000, 3000) shouldBe null // too wide to band without shrinking anyway
    }

    @Test
    fun aPhoneScreenshotGoesUpInTwoFullResolutionBands() {
        val plan = Tiling.bands(1280, 2772)!!
        plan.height shouldBe 1600
        plan.tops shouldBe listOf(0, 1172)
        // Neither band would be shrunk by the uploader.
        ImagePreparer.shouldDownscale(1280, plan.height) shouldBe false
    }

    @Test
    fun aVeryLongStripIsCappedAtTenTallerBands() {
        val plan = Tiling.bands(720, 40_000)!!
        plan.tops.size shouldBe Tiling.MAX_BANDS
        plan.tops.last() + plan.height shouldBe 40_000
    }

    @Test
    fun paragraphsInTheOverlapAreKeptOnceAndMovedToTheWholeImage() = runTest {
        // Band 0 covers rows 0..1600, band 1 rows 1172..2772; the boundary is at 1386.
        val raster = Raster(1280, 2772, IntArray(1280 * 2772))
        val scans = mutableListOf<Int>()
        val result = Tiling.scan(raster, parallel = 1) { band ->
            scans += band.height
            val index = scans.size - 1
            fun paragraph(text: String, centerYPx: Float) = Paragraph(
                text = text,
                lines = emptyList(),
                box = Box(0.5f, centerYPx / band.height, 0.5f, 40f / band.height, 0f),
                language = null,
                writingDirection = WritingDirection.LeftToRight,
                translation = null,
            )
            if (index == 0) {
                ScanResult(listOf(paragraph("top", 100f), paragraph("seam", 1300f)), "ja", band.width, band.height)
            } else {
                // "seam" again, now 1300 - 1172 = 128 px into band 1; and one near the bottom.
                ScanResult(listOf(paragraph("seam", 128f), paragraph("bottom", 1500f)), null, band.width, band.height)
            }
        }
        scans shouldBe listOf(1600, 1600)
        result.paragraphs.map { it.text } shouldBe listOf("top", "seam", "bottom")
        result.language shouldNotBe null
        result.imageHeight shouldBe 2772
        // "bottom" was 1500 px into band 1, which starts at 1172.
        result.paragraphs.last().box!!.centerY shouldBe ((1172 + 1500) / 2772f plusOrMinus 1e-4f)
        result.paragraphs.last().box!!.height shouldBe (40f / 2772f plusOrMinus 1e-5f)
    }
}

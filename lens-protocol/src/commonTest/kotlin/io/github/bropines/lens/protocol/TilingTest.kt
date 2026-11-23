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
    fun evenBandsKeepFullResolution() {
        val plan = Tiling.bands(1280, 2772)!!
        plan.heights shouldBe listOf(1600, 1600)
        plan.tops shouldBe listOf(0, 1172)
        ImagePreparer.shouldDownscale(1280, 1600) shouldBe false
    }

    @Test
    fun aVeryLongStripIsCappedAtTenTallerBands() {
        val plan = Tiling.bands(720, 40_000)!!
        plan.tops.size shouldBe Tiling.MAX_BANDS
        plan.bottom(plan.tops.lastIndex) shouldBe 40_000
    }

    @Test
    fun bandsAreCutInTheGuttersBetweenPanels() {
        // Three "panels" of busy pixels with 100-row plain gutters at 900 and 1900.
        val raster = raster(1000, 3000) { y -> y !in 900..<1000 && y !in 1900..<2000 }
        val plan = Tiling.plan(raster)!!
        plan.tops shouldBe listOf(0, 950, 1950)
        plan.heights shouldBe listOf(950, 1000, 1050)
    }

    @Test
    fun withNoGutterBandsOverlap() {
        val plan = Tiling.plan(raster(1280, 2772) { true })!!
        plan.tops shouldBe listOf(0, 1440)
        plan.heights shouldBe listOf(1600, 1332)
    }

    @Test
    fun paragraphsInTheOverlapAreKeptOnceAndMovedToTheWholeImage() = runTest {
        // No gutter: band 0 covers rows 0..1600, band 1 rows 1440..2772; they split at 1520.
        val scans = mutableListOf<Int>()
        val result = Tiling.scan(raster(1280, 2772) { true }, parallel = 1) { band ->
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
                ScanResult(listOf(paragraph("top", 100f), paragraph("seam", 1500f)), "ja", band.width, band.height)
            } else {
                // "seam" again, now 1500 - 1440 = 60 px into band 1; and one near the bottom.
                ScanResult(listOf(paragraph("seam", 60f), paragraph("bottom", 1300f)), null, band.width, band.height)
            }
        }
        scans shouldBe listOf(1600, 1332)
        result.paragraphs.map { it.text } shouldBe listOf("top", "seam", "bottom")
        result.language shouldNotBe null
        result.imageHeight shouldBe 2772
        result.paragraphs.last().box!!.centerY shouldBe ((1440 + 1300) / 2772f plusOrMinus 1e-4f)
        result.paragraphs.last().box!!.height shouldBe (40f / 2772f plusOrMinus 1e-5f)
    }

    /** White rows, except that rows where [busy] holds alternate black and white pixels. */
    private fun raster(width: Int, height: Int, busy: (Int) -> Boolean): Raster {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            val b = busy(y)
            for (x in 0 until width) argb[y * width + x] = if (b && (x / 2) % 2 == 0) black else white
        }
        return Raster(width, height, argb)
    }
}

package io.github.bropines.lens.protocol

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ImagePreparerTest {
    @Test
    fun downscalesOnlyWhenBigOnBothCounts() {
        ImagePreparer.shouldDownscale(1600, 900) shouldBe false // under the area limit
        ImagePreparer.shouldDownscale(1224, 1224) shouldBe false // over the area, under the sides
        ImagePreparer.shouldDownscale(4000, 3000) shouldBe true
        ImagePreparer.preferredSize(4000, 3000) shouldBe (1600 to 1200)
    }

    @Test
    fun opaqueRasterIsLeftAlone() {
        val raster = Raster(2, 1, intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt()))
        (ImagePreparer.flatten(raster, AlphaMode.Contrast) === raster) shouldBe true
    }

    @Test
    fun lightContentOnTransparencyGoesOntoBlack() {
        // White text on a transparent background would vanish on white.
        val raster = Raster(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0x00000000))
        val flat = ImagePreparer.flatten(raster, AlphaMode.Contrast)
        flat.argb.toList() shouldBe listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        ImagePreparer.flatten(raster, AlphaMode.White).argb[1] shouldBe 0xFFFFFFFF.toInt()
    }
}

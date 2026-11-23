package io.github.bropines.lens.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import lens.CenterRotatedBox
import lens.CoordinateType
import lens.Geometry
import lens.LensOverlayInteractionResponse
import lens.LensOverlayObjectsResponse
import lens.LensOverlayRequestId
import lens.LensOverlayServerResponse
import lens.OverlayObject
import okio.ByteString.Companion.toByteString
import kotlin.test.Test

class RegionAndObjectsTest {
    @Test
    fun regionsBecomeWholePixelsGrownOutwards() {
        Region.Whole.toPixels(640, 480) shouldBe PixelRect(0, 0, 640, 480)
        Region(0.25f, 0.5f, 0.5f, 0.25f).toPixels(400, 200) shouldBe PixelRect(100, 100, 200, 50)
        // 0.33 * 10 = 3.3 and 0.66 * 10 = 6.6: every pixel the region touches.
        Region(0.33f, 0.33f, 0.33f, 0.33f).toPixels(10, 10) shouldBe PixelRect(3, 3, 4, 4)
    }

    @Test
    fun regionsAreClampedToTheImageAndNeverEmpty() {
        Region(-0.5f, 0.5f, 1f, 1f).clamped() shouldBe Region(0f, 0.5f, 0.5f, 0.5f)
        Region(-0.5f, 0.5f, 1f, 1f).toPixels(100, 100) shouldBe PixelRect(0, 50, 50, 50)
        // Thinner than a pixel is still one pixel, and the last pixel is still inside.
        Region(0.5f, 0.5f, 0.0001f, 0.0001f).toPixels(100, 100) shouldBe PixelRect(50, 50, 1, 1)
        Region(1f, 1f, 0f, 0f).toPixels(100, 100) shouldBe PixelRect(99, 99, 1, 1)
        Region(2f, 2f, 1f, 1f).clamped().width shouldBe 0f
    }

    @Test
    fun aRegionKnowsItsCentreAndABoxItsRegion() {
        val region = Region(0.5f, 0f, 0.5f, 1f)
        (region.centerX to region.centerY) shouldBe (0.75f to 0.5f)
        Region.of(Box(centerX = 0.5f, centerY = 0.5f, width = 0.5f, height = 1f, rotation = 0.3f)) shouldBe Region(0.25f, 0f, 0.5f, 1f)
    }

    @Test
    fun croppingCopiesTheRowsOfTheRectangle() {
        // 4x3, each pixel its own index.
        val raster = Raster(4, 3, IntArray(12) { it })
        val cut = raster.crop(PixelRect(1, 1, 2, 2))
        (cut.width to cut.height) shouldBe (2 to 2)
        cut.argb.toList() shouldContainExactly listOf(5, 6, 9, 10)
        shouldThrow<IllegalArgumentException> { raster.crop(PixelRect(3, 0, 2, 1)) }
    }

    @Test
    fun objectsComeBackWithNormalisedBoxes() {
        fun geometry(cx: Float, cy: Float, w: Float, h: Float, type: CoordinateType) =
            Geometry(bounding_box = CenterRotatedBox(center_x = cx, center_y = cy, width = w, height = h, coordinate_type = type))

        val response = LensOverlayServerResponse(
            objects_response = LensOverlayObjectsResponse(
                overlay_objects = listOf(
                    OverlayObject(id = "a", geometry = geometry(0.5f, 0.25f, 0.5f, 0.5f, CoordinateType.NORMALIZED)),
                    OverlayObject(id = "b", geometry = geometry(128f, 64f, 64f, 32f, CoordinateType.IMAGE)),
                    // Not searchable without an id, not drawable without a box.
                    OverlayObject(id = "", geometry = geometry(0.5f, 0.5f, 1f, 1f, CoordinateType.NORMALIZED)),
                    OverlayObject(id = "c"),
                ),
            ),
        )
        LensResponses.parse(response, 256, 128).objects shouldContainExactly listOf(
            DetectedObject("a", Box(0.5f, 0.25f, 0.5f, 0.5f, 0f)),
            DetectedObject("b", Box(0.5f, 0.5f, 0.25f, 0.25f, 0f)),
        )
    }

    @Test
    fun aResultWithoutObjectsKeepsItsOldJsonShape() {
        val plain = ScanResult(paragraphs = emptyList(), language = null, imageWidth = 1, imageHeight = 1)
        Json.encodeToString(plain) shouldNotContain "objects"
        // And JSON written before objects existed still reads.
        Json.decodeFromString<ScanResult>("""{"paragraphs":[],"language":null,"imageWidth":1,"imageHeight":1}""") shouldBe plain
        val found = plain.copy(objects = listOf(DetectedObject("a", Box(0.5f, 0.5f, 1f, 1f, 0f))))
        Json.decodeFromString<ScanResult>(Json.encodeToString(found)) shouldBe found
    }

    @Test
    fun anInteractionWithoutTextHasNoResult() {
        val reply = LensOverlayServerResponse(interaction_response = LensOverlayInteractionResponse(encoded_response = "x"))
        LensResponses.parseInteraction(reply.encode(), 10, 10) shouldBe null
    }

    @Test
    fun vsridIsTheRequestIdInUnpaddedBase64Url() {
        // Bytes chosen so plain base64 would need '+', '/' and padding.
        val id = LensOverlayRequestId(uuid = 1, analytics_id = byteArrayOf(-5, -1, -2, -3).toByteString())
        id.encode().toList() shouldContainExactly listOf<Byte>(0x08, 0x01, 0x22, 0x04, -5, -1, -2, -3)
        LensSearchUrls.vsrid(id) shouldBe "CAEiBPv__v0"
    }

    @Test
    fun queryValuesArePercentEncodedAsUtf8() {
        LensSearchUrls.percentEncode("a b&c=d/é~_-.") shouldBe "a%20b%26c%3Dd%2F%C3%A9~_-."
        LensSearchUrls.text("x", SearchPageOptions(darkMode = false)) shouldBe
            "https://www.google.com/search?q=x&lns_fp=1&lns_mode=text&lns_surface=42&cs=0"
    }
}

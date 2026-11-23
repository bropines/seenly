package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.test.Test

class GeometryTest {
    private val quarterTurn = (PI / 2).toFloat()
    private val imageSize = Size(1000f, 500f)

    private infix fun Float.near(expected: Float) = this shouldBe (expected plusOrMinus 0.01f)

    private fun paragraph(box: Box?, vararg lines: Box) = Paragraph(
        text = "",
        lines = lines.map { Line(text = "", words = emptyList(), box = it, translation = null, translationStyle = null) },
        box = box,
        language = null,
        writingDirection = WritingDirection.LeftToRight,
        translation = null,
    )

    private fun scan(vararg paragraphs: Paragraph) = ScanResult(paragraphs.toList(), null, 1000, 500)

    // ---- boxes ---------------------------------------------------------------

    @Test
    fun `a normalised box scales to the image`() {
        Box(0.5f, 0.2f, 0.4f, 0.1f, 0.3f).toPixels(1000f, 500f) shouldBe OrientedBox(500f, 100f, 400f, 50f, 0.3f)
    }

    @Test
    fun `both patch paddings are fractions of the line height, split over two sides`() {
        val line = OrientedBox(500f, 100f, 400f, 50f, 0.3f)
        val patch = patchBox(line, horizontalPadding = 0.4f, verticalPadding = 0.2f)
        // 0.4 of the 50px height, not of the 400px width.
        patch.width near 420f
        patch.height near 60f
        // Centre and angle are the line's: half the padding lands on each side.
        patch.center shouldBe line.center
        patch.rotation shouldBe line.rotation
        patch.frame.left near 290f
        patch.frame.top near 70f
    }

    @Test
    fun `a patch without padding is the line box`() {
        val line = OrientedBox(10f, 10f, 8f, 4f, 0f)
        patchBox(line, 0f, 0f) shouldBe line
    }

    @Test
    fun `corners turn clockwise with the box`() {
        // A 100x20 box turned a quarter turn clockwise stands 20 wide and 100 tall,
        // and its top-left corner ends up top-right.
        val corners = OrientedBox(100f, 100f, 100f, 20f, quarterTurn).corners()
        corners[0].x near 110f
        corners[0].y near 50f
        corners[2].x near 90f
        corners[2].y near 150f
        val bounds = OrientedBox(100f, 100f, 100f, 20f, quarterTurn).bounds()
        bounds.width near 20f
        bounds.height near 100f
    }

    @Test
    fun `containment follows the rotation`() {
        val flat = OrientedBox(100f, 100f, 100f, 20f, 0f)
        val upright = flat.copy(rotation = quarterTurn)
        val alongTheLine = Offset(140f, 100f)
        val acrossTheLine = Offset(100f, 140f)
        flat.contains(alongTheLine) shouldBe true
        flat.contains(acrossTheLine) shouldBe false
        upright.contains(alongTheLine) shouldBe false
        upright.contains(acrossTheLine) shouldBe true
        // Slop reaches a little past the edge, and no further.
        flat.contains(Offset(100f, 114f)) shouldBe false
        flat.contains(Offset(100f, 114f), slop = 5f) shouldBe true
        flat.contains(Offset(100f, 116f), slop = 5f) shouldBe false
    }

    @Test
    fun `a diagonal box is hit along its diagonal only`() {
        val diagonal = OrientedBox(100f, 100f, 120f, 10f, (PI / 4).toFloat())
        // Clockwise with y down: the box runs from upper left to lower right.
        diagonal.contains(Offset(130f, 130f)) shouldBe true
        diagonal.contains(Offset(130f, 70f)) shouldBe false
    }

    // ---- hit testing ---------------------------------------------------------

    @Test
    fun `a tap finds the paragraph under it`() {
        val result = scan(
            paragraph(Box(0.25f, 0.5f, 0.3f, 0.2f, 0f)),
            paragraph(Box(0.75f, 0.5f, 0.3f, 0.2f, 0f)),
        )
        result.paragraphAt(Offset(250f, 250f), imageSize) shouldBe 0
        result.paragraphAt(Offset(750f, 250f), imageSize) shouldBe 1
        result.paragraphAt(Offset(500f, 250f), imageSize) shouldBe null
        result.paragraphAt(Offset(250f, 20f), imageSize) shouldBe null
    }

    @Test
    fun `a rotated paragraph is hit as rotated`() {
        // 400x50 px, standing on end: it now covers x 475..525 and y 50..450.
        val result = scan(paragraph(Box(0.5f, 0.5f, 0.4f, 0.1f, quarterTurn)))
        result.paragraphAt(Offset(500f, 420f), imageSize) shouldBe 0
        // Inside the unrotated box, outside the rotated one.
        result.paragraphAt(Offset(650f, 250f), imageSize) shouldBe null
    }

    @Test
    fun `the smaller of two overlapping paragraphs wins`() {
        val result = scan(
            paragraph(Box(0.5f, 0.5f, 0.8f, 0.8f, 0f)),
            paragraph(Box(0.5f, 0.5f, 0.1f, 0.1f, 0f)),
        )
        result.paragraphAt(Offset(500f, 250f), imageSize) shouldBe 1
        result.paragraphAt(Offset(200f, 250f), imageSize) shouldBe 0
    }

    @Test
    fun `slop only helps when nothing is hit exactly`() {
        val result = scan(
            paragraph(Box(0.25f, 0.5f, 0.3f, 0.2f, 0f)),
            paragraph(Box(0.75f, 0.5f, 0.3f, 0.2f, 0f)),
        )
        // 10 px left of the first paragraph's left edge (x = 100).
        result.paragraphAt(Offset(90f, 250f), imageSize) shouldBe null
        result.paragraphAt(Offset(90f, 250f), imageSize, slop = 20f) shouldBe 0
        // An exact hit is not stolen by a smaller neighbour within slop.
        val nested = scan(
            paragraph(Box(0.3f, 0.5f, 0.4f, 0.4f, 0f)),
            paragraph(Box(0.55f, 0.5f, 0.05f, 0.05f, 0f)),
        )
        nested.paragraphAt(Offset(495f, 250f), imageSize, slop = 40f) shouldBe 0
    }

    @Test
    fun `a paragraph without its own box is hit through its lines`() {
        val result = scan(paragraph(null, Box(0.5f, 0.2f, 0.4f, 0.1f, 0f), Box(0.5f, 0.4f, 0.4f, 0.1f, 0f)))
        result.paragraphAt(Offset(500f, 200f), imageSize) shouldBe 0
        result.paragraphAt(Offset(500f, 150f), imageSize) shouldBe null
        scan().paragraphAt(Offset(1f, 1f), imageSize) shouldBe null
    }

    // ---- hull and room -------------------------------------------------------

    @Test
    fun `the hull drops interior and duplicate points`() {
        val hull = convexHull(
            listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f), Offset(5f, 5f), Offset(10f, 0f)),
        )
        hull.toSet() shouldBe setOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        hull.size shouldBe 4
        convexHull(listOf(Offset(1f, 1f), Offset(2f, 2f))).size shouldBe 2
    }

    @Test
    fun `room grows about the centre and stops at the image`() {
        val own = Rect(100f, 100f, 200f, 200f)
        roomFor(own, emptyList(), growth = 1.5f, imageWidth = 1000f, imageHeight = 1000f) shouldBe
            Rect(75f, 75f, 225f, 225f)
        roomFor(Rect(0f, 0f, 100f, 100f), emptyList(), 2f, 120f, 1000f) shouldBe Rect(0f, 0f, 120f, 150f)
    }

    @Test
    fun `room stops short of a neighbour beside it`() {
        val own = Rect(100f, 100f, 200f, 200f)
        val right = Rect(210f, 120f, 300f, 180f)
        val unrelated = Rect(210f, 500f, 300f, 600f)
        val room = roomFor(own, listOf(right, unrelated), growth = 2f, imageWidth = 1000f, imageHeight = 1000f)
        room.right shouldBe 208f
        room.left shouldBe 50f
        // An overlapping neighbour never shrinks the box below itself.
        roomFor(own, listOf(Rect(150f, 150f, 400f, 400f)), 1.5f, 1000f, 1000f) shouldBe Rect(75f, 75f, 225f, 225f)
    }

    // ---- view ----------------------------------------------------------------

    @Test
    fun `the image is fitted and centred`() {
        fitRect(200, 100, Size(400f, 400f)) shouldBe Rect(0f, 100f, 400f, 300f)
        fitRect(100, 200, Size(400f, 400f)) shouldBe Rect(100f, 0f, 300f, 400f)
        fitRect(0, 100, Size(400f, 400f)) shouldBe Rect.Zero
    }

    @Test
    fun `the view fills bounded space and keeps the image's shape otherwise`() {
        measureImage(200, 100, Constraints(maxWidth = 300, maxHeight = 500)) shouldBe IntSize(300, 500)
        measureImage(200, 100, Constraints(maxWidth = 300)) shouldBe IntSize(300, 150)
        measureImage(200, 100, Constraints(maxHeight = 50)) shouldBe IntSize(100, 50)
        measureImage(200, 100, Constraints()) shouldBe IntSize(200, 100)
    }

    @Test
    fun `an unzoomed image stays where it was fitted`() {
        val viewport = Size(400f, 400f)
        val content = Rect(0f, 100f, 400f, 300f)
        clampOffset(Offset(50f, -30f), 1f, viewport, content) shouldBe Offset.Zero
    }

    @Test
    fun `a zoomed image pans only until its edge meets the view`() {
        val viewport = Size(400f, 400f)
        val content = Rect(0f, 100f, 400f, 300f)
        // At 4x the image is 1600x800 and starts at y = 400 in zoomed space.
        clampOffset(Offset(100f, 0f), 4f, viewport, content) shouldBe Offset(0f, -400f)
        clampOffset(Offset(-5000f, -5000f), 4f, viewport, content) shouldBe Offset(-1200f, -800f)
        clampOffset(Offset(-600f, -600f), 4f, viewport, content) shouldBe Offset(-600f, -600f)
        // At 1.5x it is still shorter than the view, so that axis is centred.
        clampOffset(Offset(-100f, 77f), 1.5f, viewport, content) shouldBe Offset(-100f, -100f)
    }

    @Test
    fun `zooming keeps the point under the fingers`() {
        val zoom = ZoomState()
        zoom.updateBounds(Size(400f, 400f), Rect(0f, 0f, 400f, 400f))
        val focus = Offset(300f, 100f)
        val before = zoom.toUnzoomed(focus)
        zoom.transformBy(focus, Offset.Zero, 2f)
        zoom.scale shouldBe 2f
        zoom.toUnzoomed(focus) shouldBe before
        zoom.transformBy(focus, Offset.Zero, 100f)
        zoom.scale shouldBe zoom.maxScale
        zoom.transformBy(focus, Offset.Zero, 0.0001f)
        zoom.scale shouldBe 1f
        zoom.offset shouldBe Offset.Zero
    }
}

package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Rect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class BubbleTest {
    /** Lines of [widths] px, 20 px tall, stacked 24 px apart and centred on x = 100. */
    private fun stack(vararg widths: Float, axis: Float = 100f) =
        widths.mapIndexed { i, w -> Rect(axis - w / 2, 100f + i * 24f, axis + w / 2, 120f + i * 24f) }

    private fun bounds(rects: List<Rect>) =
        Rect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })

    @Test
    fun `a stack of short centred lines is a bubble column`() {
        val lines = stack(60f, 80f, 50f)
        isBubbleColumn(lines, bounds(lines)) shouldBe true
    }

    @Test
    fun `captions, rows, strips and left-aligned text are not`() {
        // Two lines: a caption, unless the caller lowers the bar.
        stack(60f, 80f).let { isBubbleColumn(it, bounds(it)) shouldBe false }
        stack(60f, 80f).let { isBubbleColumn(it, bounds(it), minLines = 2) shouldBe true }
        // Side by side: a table row.
        val row = listOf(Rect(0f, 0f, 50f, 20f), Rect(60f, 0f, 110f, 20f), Rect(120f, 2f, 170f, 22f))
        isBubbleColumn(row, bounds(row)) shouldBe false
        // Body text: lines thirty heights long.
        stack(600f, 600f, 600f).let { isBubbleColumn(it, bounds(it)) shouldBe false }
        // Left-aligned with a short last line.
        val ragged = listOf(Rect(0f, 0f, 200f, 20f), Rect(0f, 24f, 200f, 44f), Rect(0f, 48f, 60f, 68f))
        isBubbleColumn(ragged, bounds(ragged)) shouldBe false
    }

    @Test
    fun `lines collide only once the floor raises them into each other`() {
        val lines = stack(60f, 60f, 60f)
        // Fitted at 16px, 24px apart: clear.
        collidesAtFloor(lines, listOf(16f, 16f, 16f), floor = 0f) shouldBe false
        collidesAtFloor(lines, listOf(16f, 16f, 16f), floor = 20f) shouldBe false
        // Raised to 30px, 24px apart: through each other.
        collidesAtFloor(lines, listOf(16f, 16f, 16f), floor = 30f) shouldBe true
        crowdsAtFloor(listOf(16f, 40f), 20f) shouldBe true
        crowdsAtFloor(listOf(16f, 40f), 0f) shouldBe false
    }

    @Test
    fun `band room stops only at neighbours in the same band`() {
        val own = Rect(100f, 100f, 200f, 200f)
        val beside = Rect(250f, 120f, 300f, 180f)
        val diagonal = Rect(0f, 0f, 50f, 50f)
        val room = bandRoom(own, listOf(beside, diagonal), 3f, 1000f, 1000f)
        room.right shouldBe 248f
        room.left shouldBe 0f
        room.top shouldBe 0f
    }

    /** A white disc of radius 60 with a dark 3px ring, centred in a 200x200 grey grid. */
    private fun disc(gap: Boolean = false): IntArray = IntArray(200 * 200) { i ->
        val x = i % 200 - 100f
        val y = i / 200 - 100f
        val r = kotlin.math.sqrt(x * x + y * y)
        when {
            r < 60f -> ARGB_WHITE
            r < 63f && !(gap && x > 55f && kotlin.math.abs(y) < 4f) -> ARGB_BLACK
            gap -> ARGB_WHITE
            else -> 0xFF808080.toInt()
        }
    }

    private val open = Sides(left = true, top = true, right = true, bottom = true)

    @Test
    fun `the bubble is read from its outline, one span per row`() {
        val lines = listOf(Rect(80f, 85f, 120f, 97f), Rect(78f, 100f, 122f, 112f))
        val mask = bubbleMask(disc(), 200, 200, lines, emptyList(), open)!!
        // The rows run from near the top of the disc to near its bottom; the
        // narrowest rows at either end are trimmed as a tail would be.
        (mask.top in 39..47) shouldBe true
        (mask.bottom in 153..161) shouldBe true
        // ...and the middle row is the disc's diameter.
        (mask.left[100] in 39..42) shouldBe true
        (mask.right[100] in 158..161) shouldBe true
    }

    @Test
    fun `a bubble with a gap in its outline is no bubble`() {
        val lines = listOf(Rect(80f, 85f, 120f, 97f))
        bubbleMask(disc(gap = true), 200, 200, lines, emptyList(), open) shouldBe null
        // Text over artwork with no dominant colour has no bubble either.
        val noise = IntArray(200 * 200) { (it * 2654435761L).toInt() or ARGB_BLACK }
        bubbleMask(noise, 200, 200, lines, emptyList(), open) shouldBe null
    }

    @Test
    fun `a mask that does not hold every line the text stood on is refused`() {
        // White, with a dark bar right across between the two lines: whatever
        // the fill found, it is not one area holding both.
        val pixels = IntArray(200 * 200) { i -> if (i / 200 in 98..101) ARGB_BLACK else ARGB_WHITE }
        val lines = listOf(Rect(80f, 80f, 120f, 95f), Rect(80f, 104f, 120f, 119f))
        val closed = Sides(left = false, top = false, right = false, bottom = false)
        bubbleMask(pixels, 200, 200, lines, emptyList(), closed) shouldBe null
    }

    /** Every character is half the size wide; a space too. */
    private val monospace = { s: String, size: Float -> s.length * size * 0.5f }

    @Test
    fun `a shaped fit keeps every line inside the shape`() {
        val shape = ellipseShape(100f, 100f, 80f, 60f)
        val text = "the quick brown fox jumps over the lazy dog again and again"
        val fit = fitShaped(monospace, text, shape, REFLOW_LINE_SPACING, perCharacter = false, maxSize = 40f)
        fit shouldNotBe null
        fit!!.lines.joinToString(" ") { it.text } shouldBe text
        for (line in fit.lines) {
            val span = shape.span(line.y, line.y + fit.size)!!
            (line.left >= span.first && line.right <= span.second) shouldBe true
            (monospace(line.text, fit.size) <= line.right - line.left + 0.5f) shouldBe true
        }
        // Nothing larger fits: the next size up fails or the search would have taken it.
        (fit.size < 40f) shouldBe true
    }

    /** A rectangle as a shape. */
    private fun rectShape(left: Float, top: Float, right: Float, bottom: Float, notch: ClosedFloatingPointRange<Float>? = null) =
        object : TextShape {
            override val top = top
            override val bottom = bottom
            override fun span(y0: Float, y1: Float): Pair<Float, Float>? {
                if (y0 < top || y1 > bottom) return null
                // A notch cuts the left side back to x = 40 on its rows.
                val l = if (notch != null && y1 > notch.start && y0 < notch.endInclusive) 40f else left
                return l to right
            }
        }

    @Test
    fun `the symmetric shape is centred on the axis and does not jump at a notch`() {
        val shape = symmetricShape(rectShape(0f, 0f, 100f, 200f, notch = 90f..110f), axis = 50f)
        shape.span(10f, 30f) shouldBe (0f to 100f)
        // On the notch's rows, and on rows near enough to it, both sides come in.
        shape.span(95f, 105f) shouldBe (40f to 60f)
        shape.span(112f, 130f) shouldBe (40f to 60f)
        // An axis off centre keeps the narrower half on both sides.
        symmetricShape(rectShape(0f, 0f, 100f, 200f), axis = 30f).span(10f, 30f) shouldBe (0f to 60f)
    }

    @Test
    fun `lines are balanced and the last word is never left alone`() {
        // Greedy at 10px: "aaaa bbbb" (45) then "cc"; balanced: "aaaa" / "bbbb cc".
        val shape = rectShape(0f, 0f, 47f + 2 * 2.5f, 1000f)
        val fit = fitShaped(monospace, "aaaa bbbb cc", shape, 1.25f, false, maxSize = 10f, minSize = 10f)!!
        fit.lines.map { it.text } shouldBe listOf("aaaa", "bbbb cc")
    }

    @Test
    fun `a word wider than the shape at every size does not fit`() {
        fitShaped(monospace, "a".repeat(200), ellipseShape(0f, 0f, 20f, 20f), 1.25f, false, 40f) shouldBe null
    }

    @Test
    fun `characters are the tokens only for languages written without spaces`() {
        wrapsPerCharacter("ja") shouldBe true
        wrapsPerCharacter("th") shouldBe true
        wrapsPerCharacter("ru") shouldBe false
        wrapTokens("при вет", perCharacter = false) shouldBe listOf("при", "вет")
        wrapTokens("日本語", perCharacter = true) shouldBe listOf("日", "本", "語")
    }
}

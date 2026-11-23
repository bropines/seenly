package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.Word
import io.github.bropines.lens.protocol.WritingDirection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.test.Test

class TextSelectionTest {
    private val quarterTurn = (PI / 2).toFloat()

    private infix fun Float.near(expected: Float) = this shouldBe (expected plusOrMinus 0.01f)

    private fun word(text: String, separator: String? = " ", box: Box? = null) = Word(text, separator, box)

    private fun line(vararg words: Word, box: Box? = null) =
        Line(text = "", words = words.toList(), box = box, translation = null, translationStyle = null)

    private fun paragraph(vararg lines: Line, direction: WritingDirection = WritingDirection.LeftToRight) =
        Paragraph("", lines.toList(), box = null, language = null, writingDirection = direction, translation = null)

    private fun scan(vararg paragraphs: Paragraph, width: Int = 1000, height: Int = 1000) =
        ScanResult(paragraphs.toList(), null, width, height)

    /** "Hello big world / again" and "Second one": words 0..3 and 4..5. */
    private val twoParagraphs = scan(
        paragraph(
            line(word("Hello"), word("big"), word("world", separator = "")),
            line(word("again", separator = null)),
        ),
        paragraph(line(word("Second"), word("one", separator = ""))),
    )

    // ---- reading order -------------------------------------------------------

    @Test
    fun `words are numbered paragraph by paragraph, line by line`() {
        val index = WordIndex(twoParagraphs)
        index.size shouldBe 6
        twoParagraphs.wordCount shouldBe 6
        index.refs shouldBe listOf(
            WordRef(0, 0, 0), WordRef(0, 0, 1), WordRef(0, 0, 2),
            WordRef(0, 1, 0),
            WordRef(1, 0, 0), WordRef(1, 0, 1),
        )
    }

    @Test
    fun `empty lines and paragraphs take no numbers`() {
        val result = scan(paragraph(line(), line(word("a"))), paragraph(), paragraph(line(word("b"))))
        WordIndex(result).refs shouldBe listOf(WordRef(0, 1, 0), WordRef(2, 0, 0))
        result.wordRangeOfParagraph(0) shouldBe WordRange(0, 0)
        result.wordRangeOfParagraph(1) shouldBe null
        result.wordRangeOfParagraph(2) shouldBe WordRange(1, 1)
    }

    @Test
    fun `a paragraph maps to the range of its words`() {
        twoParagraphs.wordRangeOfParagraph(0) shouldBe WordRange(0, 3)
        twoParagraphs.wordRangeOfParagraph(1) shouldBe WordRange(4, 5)
        twoParagraphs.wordRangeOfParagraph(2) shouldBe null
        twoParagraphs.wordRangeOfParagraph(-1) shouldBe null
    }

    // ---- ranges ----------------------------------------------------------------

    @Test
    fun `a range dragged backwards is normalised`() {
        WordRange.between(5, 2) shouldBe WordRange(2, 5)
        WordRange.between(2, 5) shouldBe WordRange(2, 5)
        WordRange.between(3, 3) shouldBe WordRange(3, 3)
        WordRange.between(3, 3).count shouldBe 1
        WordRange(2, 5).count shouldBe 4
        (4 in WordRange(2, 5)) shouldBe true
        (6 in WordRange(2, 5)) shouldBe false
    }

    @Test
    fun `a range cannot be built inside out or negative`() {
        shouldThrow<IllegalArgumentException> { WordRange(5, 2) }
        shouldThrow<IllegalArgumentException> { WordRange(-1, 2) }
    }

    @Test
    fun `dragging past the anchor flips the range around it`() {
        val state = TextSelectionState()
        // The anchor is word 3; the finger moves forward, back across it, and on.
        state.dragTo(3, 5) shouldBe true
        state.selection shouldBe WordRange(3, 5)
        state.dragTo(3, 5) shouldBe false
        state.dragTo(3, 3) shouldBe true
        state.selection shouldBe WordRange(3, 3)
        state.dragTo(3, 1) shouldBe true
        state.selection shouldBe WordRange(1, 3)
    }

    @Test
    fun `a range is cut down to the words that exist`() {
        val index = WordIndex(twoParagraphs)
        index.clamp(WordRange(2, 99)) shouldBe WordRange(2, 5)
        index.clamp(WordRange(6, 99)) shouldBe null
        WordIndex(scan()).clamp(WordRange(0, 0)) shouldBe null
    }

    // ---- text ------------------------------------------------------------------

    @Test
    fun `words join with their separators, lines with a break, paragraphs with a blank line`() {
        twoParagraphs.textOf(WordRange(0, 5)) shouldBe "Hello big world\nagain\n\nSecond one"
    }

    @Test
    fun `nothing follows the last selected word`() {
        twoParagraphs.textOf(WordRange(0, 0)) shouldBe "Hello"
        twoParagraphs.textOf(WordRange(0, 1)) shouldBe "Hello big"
        twoParagraphs.textOf(WordRange(1, 2)) shouldBe "big world"
    }

    @Test
    fun `a separator at a line end gives way to the line break`() {
        val result = scan(paragraph(line(word("one"), word("two", separator = " ")), line(word("three"))))
        result.textOf(WordRange(0, 2)) shouldBe "one two\nthree"
    }

    @Test
    fun `a selection across a paragraph boundary keeps the blank line`() {
        twoParagraphs.textOf(WordRange(3, 4)) shouldBe "again\n\nSecond"
    }

    @Test
    fun `scripts without word spacing join with nothing`() {
        val result = scan(
            paragraph(
                line(word("日本", separator = ""), word("語", separator = null), word("です", separator = "")),
                line(word("ね", separator = null)),
            ),
        )
        result.textOf(WordRange(0, 3)) shouldBe "日本語です\nね"
        result.textOf(WordRange(1, 2)) shouldBe "語です"
    }

    @Test
    fun `text past the last word is ignored`() {
        twoParagraphs.textOf(WordRange(4, 99)) shouldBe "Second one"
        twoParagraphs.textOf(WordRange(50, 99)) shouldBe ""
    }

    @Test
    fun `the state reports the selected text`() {
        val state = TextSelectionState()
        state.selectedText shouldBe null
        state.selectAll(twoParagraphs)
        state.selection shouldBe WordRange(0, 5)
        state.selectedText shouldBe "Hello big world\nagain\n\nSecond one"
        state.selectParagraph(1) shouldBe true
        state.selectedText shouldBe "Second one"
        state.selectParagraph(7) shouldBe false
        state.selection shouldBe WordRange(4, 5)
        state.select(WordRange(3, 40))
        state.selection shouldBe WordRange(3, 5)
        state.clear()
        state.selection shouldBe null
        // Another result starts from nothing.
        state.selectAll(scan())
        state.selection shouldBe null
        state.selectParagraph(0) shouldBe false
    }

    // ---- word hit-testing --------------------------------------------------------

    /** One line at y = 100 with three 100x40 px words at x = 100, 250 and 400. */
    private val threeWords = scan(
        paragraph(
            line(
                word("a", box = Box(0.10f, 0.1f, 0.1f, 0.04f, 0f)),
                word("b", box = Box(0.25f, 0.1f, 0.1f, 0.04f, 0f)),
                word("c", box = Box(0.40f, 0.1f, 0.1f, 0.04f, 0f)),
                box = Box(0.25f, 0.1f, 0.4f, 0.06f, 0f),
            ),
        ),
    )

    @Test
    fun `a point picks the word under it, then the nearest`() {
        val layout = WordLayout(threeWords, 1000f, 1000f)
        layout.nearestWord(Offset(250f, 100f)) shouldBe 1
        layout.nearestWord(Offset(60f, 100f)) shouldBe 0
        // In the gap between b (ends at 300) and c (starts at 350), nearer to c.
        layout.nearestWord(Offset(330f, 100f)) shouldBe 2
        // Far below the line the nearest word still wins, as a drag needs...
        layout.nearestWord(Offset(410f, 900f)) shouldBe 2
        // ...but a long press has to land close.
        layout.nearestWord(Offset(410f, 900f), maxDistance = 30f) shouldBe null
        layout.nearestWord(Offset(410f, 135f), maxDistance = 30f) shouldBe 2
    }

    @Test
    fun `rotated words are hit as rotated`() {
        // Two 200x40 px words turned a quarter turn: they stand 40 wide and 200
        // tall, at x = 300 and x = 500, although unrotated they would overlap x = 400.
        val result = scan(
            paragraph(
                line(
                    word("up", box = Box(0.3f, 0.5f, 0.2f, 0.04f, quarterTurn)),
                    word("down", box = Box(0.5f, 0.5f, 0.2f, 0.04f, quarterTurn)),
                ),
            ),
        )
        val layout = WordLayout(result, 1000f, 1000f)
        val up = layout.wordBoxes[0]!!
        up.distanceTo(Offset(300f, 590f)) near 0f
        up.distanceTo(Offset(340f, 500f)) near 20f
        layout.nearestWord(Offset(310f, 420f)) shouldBe 0
        layout.nearestWord(Offset(490f, 580f)) shouldBe 1
        // Inside both boxes as they would lie unrotated, inside neither as they stand.
        layout.nearestWord(Offset(400f, 500f), maxDistance = 50f) shouldBe null
        layout.nearestWord(Offset(385f, 500f)) shouldBe 0
    }

    @Test
    fun `words without boxes share their line's box by length`() {
        // A 400x50 px line at x 100..500: "ab" gets a quarter, "cdefgh" the rest.
        val result = scan(paragraph(line(word("ab"), word("cdefgh"), box = Box(0.3f, 0.5f, 0.4f, 0.05f, 0f))))
        val boxes = WordLayout(result, 1000f, 1000f).wordBoxes.map { it!!.frame }
        boxes shouldBe listOf(Rect(100f, 475f, 200f, 525f), Rect(200f, 475f, 500f, 525f))
        // Right to left the first word takes the right end.
        val rtl = scan(
            paragraph(
                line(word("ab"), word("cdefgh"), box = Box(0.3f, 0.5f, 0.4f, 0.05f, 0f)),
                direction = WritingDirection.RightToLeft,
            ),
        )
        WordLayout(rtl, 1000f, 1000f).wordBoxes[0]!!.frame shouldBe Rect(400f, 475f, 500f, 525f)
        // A vertical column is split from the top.
        val column = scan(
            paragraph(
                line(word("ab"), word("cdefgh"), box = Box(0.5f, 0.5f, 0.05f, 0.4f, 0f)),
                direction = WritingDirection.TopToBottom,
            ),
        )
        WordLayout(column, 1000f, 1000f).wordBoxes[0]!!.frame shouldBe Rect(475f, 300f, 525f, 400f)
    }

    // ---- merging -------------------------------------------------------------------

    @Test
    fun `selected words of a line merge into one strip as thick as the line`() {
        val layout = WordLayout(threeWords, 1000f, 1000f)
        // b and c: x 200..450. The words are 40 px tall, the line 60: the strip takes the line's.
        val strip = layout.lineSelections(WordRange(1, 2)).single()
        strip.flow shouldBe LineFlow.LeftToRight
        strip.box.frame shouldBe Rect(200f, 70f, 450f, 130f)
        // One word alone is still as thick as the line.
        layout.lineSelections(WordRange(0, 0)).single().box.frame shouldBe Rect(50f, 70f, 150f, 130f)
        // The whole line selected is the line's own box.
        layout.lineSelections(WordRange(0, 2)).single().box.frame shouldBe Rect(50f, 70f, 450f, 130f)
    }

    @Test
    fun `a selection over several lines gives one strip per line`() {
        val result = scan(
            paragraph(
                line(
                    word("a", box = Box(0.1f, 0.1f, 0.1f, 0.04f, 0f)),
                    word("b", box = Box(0.3f, 0.1f, 0.1f, 0.04f, 0f)),
                ),
                line(
                    word("c", box = Box(0.1f, 0.2f, 0.1f, 0.04f, 0f)),
                    word("d", box = Box(0.3f, 0.2f, 0.1f, 0.04f, 0f)),
                ),
            ),
            paragraph(line(word("e", box = Box(0.7f, 0.5f, 0.1f, 0.04f, 0f)))),
        )
        val layout = WordLayout(result, 1000f, 1000f)
        // From b to e: the end of line one, all of line two, and the other paragraph.
        layout.lineSelections(WordRange(1, 4)).map { it.box.frame } shouldBe listOf(
            Rect(250f, 80f, 350f, 120f),
            Rect(50f, 180f, 350f, 220f),
            Rect(650f, 480f, 750f, 520f),
        )
        layout.bounds(WordRange(1, 4)) shouldBe Rect(50f, 80f, 750f, 520f)
        layout.bounds(WordRange(9, 9)) shouldBe null
    }

    @Test
    fun `merging keeps the line's angle`() {
        // A line turned a quarter turn, running down the image at x = 500.
        val line = OrientedBox(500f, 500f, 400f, 60f, quarterTurn)
        val first = OrientedBox(500f, 350f, 100f, 40f, quarterTurn)
        val second = OrientedBox(500f, 480f, 120f, 40f, quarterTurn)
        val merged = mergeWordBoxes(line, listOf(first, second))!!
        merged.rotation shouldBe quarterTurn
        // Along the line: from the first word's start (y = 300) to the second's end (y = 540).
        merged.width near 240f
        merged.height near 60f
        merged.centerX near 500f
        merged.centerY near 420f
        // Without a line box the words alone decide both extents.
        val bare = mergeWordBoxes(null, listOf(first, second))!!
        bare.width near 240f
        bare.height near 40f
        mergeWordBoxes(null, emptyList()) shouldBe null
    }

    // ---- handles -------------------------------------------------------------------

    @Test
    fun `handles hang below the ends of a horizontal line`() {
        val strip = LineSelection(OrientedBox(300f, 100f, 200f, 40f, 0f), LineFlow.LeftToRight)
        val start = handlePlacement(strip, SelectionEnd.Start, radius = 10f)
        val end = handlePlacement(strip, SelectionEnd.End, radius = 10f)
        start.anchor shouldBe Offset(200f, 120f)
        start.center shouldBe Offset(190f, 130f)
        start.grip shouldBe Offset(200f, 100f)
        end.anchor shouldBe Offset(400f, 120f)
        end.center shouldBe Offset(410f, 130f)
        // Right to left, the selection starts at the right.
        val rtl = strip.copy(flow = LineFlow.RightToLeft)
        handlePlacement(rtl, SelectionEnd.Start, 10f).anchor shouldBe Offset(400f, 120f)
        handlePlacement(rtl, SelectionEnd.End, 10f).center shouldBe Offset(190f, 130f)
    }

    @Test
    fun `handles sit at the top and bottom of a column, and turn with a rotated line`() {
        val column = LineSelection(OrientedBox(300f, 300f, 40f, 200f, 0f), LineFlow.TopToBottom)
        handlePlacement(column, SelectionEnd.Start, 10f).anchor shouldBe Offset(320f, 200f)
        handlePlacement(column, SelectionEnd.Start, 10f).grip shouldBe Offset(300f, 200f)
        handlePlacement(column, SelectionEnd.End, 10f).center shouldBe Offset(270f, 410f)
        // A horizontal line turned a quarter turn reads downwards: its "below" is to the left.
        val turned = LineSelection(OrientedBox(300f, 300f, 200f, 40f, quarterTurn), LineFlow.LeftToRight)
        val start = handlePlacement(turned, SelectionEnd.Start, 10f)
        start.anchor.x near 280f
        start.anchor.y near 200f
        start.center.x near 270f
        start.center.y near 190f
    }

    // ---- view mapping --------------------------------------------------------------

    @Test
    fun `the selection's bounds follow fit, zoom and pan`() {
        val layout = WordLayout(threeWords, 1000f, 1000f)
        val zoom = ZoomState()
        val state = TextSelectionState()
        // A 1000 px image fitted into a 500 px view: half scale, no letterbox.
        val fit = Rect(0f, 0f, 500f, 500f)
        zoom.updateBounds(Size(500f, 500f), fit)
        state.bind(layout, 1000f, fit, zoom)
        state.boundsInView shouldBe null
        state.selectParagraph(0) shouldBe true
        // The line is x 50..450, y 70..130 in the image.
        state.boundsInView shouldBe Rect(25f, 35f, 225f, 65f)
        state.viewScale shouldBe 0.5f
        state.viewToImage(Offset(25f, 35f)) shouldBe Offset(50f, 70f)

        zoom.transformBy(Offset.Zero, Offset.Zero, 2f)
        state.boundsInView shouldBe Rect(50f, 70f, 450f, 130f)
        state.viewScale shouldBe 1f
        zoom.transformBy(Offset.Zero, Offset(-100f, -20f), 1f)
        state.boundsInView shouldBe Rect(-50f, 50f, 350f, 110f)
        state.imageToView(state.viewToImage(Offset(123f, 45f))) shouldBe Offset(123f, 45f)

        // Showing another result, or none, drops the selection.
        state.bind(null, 1000f, fit, zoom)
        state.selection shouldBe null
        state.boundsInView shouldBe null
    }
}

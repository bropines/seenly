package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ParagraphTranslation
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.WritingDirection
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingOrderTest {
    private fun bubble(
        name: String,
        x: Float,
        y: Float,
        width: Float = 0.1f,
        height: Float = 0.2f,
        direction: WritingDirection = WritingDirection.TopToBottom,
        rotation: Float = 0f,
        translated: Boolean = true,
    ) = Paragraph(
        text = name,
        lines = listOf(Line(name, emptyList(), null, null, null)),
        box = Box(x, y, width, height, rotation),
        language = "ja",
        writingDirection = direction,
        translation = if (translated) {
            ParagraphTranslation("[$name]", "ja", "en", WritingDirection.LeftToRight, TextAlignment.Center, false)
        } else {
            null
        },
    )

    private fun page(vararg paragraphs: Paragraph) = ScanResult(paragraphs.toList(), "ja", 1000, 1500)

    private fun ScanResult.order() = inReadingOrder().map { it.text }

    @Test
    fun aMangaPageIsReadInRowsFromTheTopAndEachRowFromTheRight() {
        // Lens's order here is left to right, bottom row first: wrong both ways for manga.
        val result = page(
            bubble("bottom-left", 0.2f, 0.8f),
            bubble("bottom-right", 0.8f, 0.78f),
            bubble("top-left", 0.2f, 0.2f),
            bubble("top-middle", 0.5f, 0.24f),
            bubble("top-right", 0.8f, 0.18f),
        )
        assertEquals(listOf("top-right", "top-middle", "top-left", "bottom-right", "bottom-left"), result.order())
        assertEquals("top-right\ntop-middle\ntop-left\nbottom-right\nbottom-left", result.readableText())
        assertEquals("[top-right]\n[top-middle]\n[top-left]\n[bottom-right]\n[bottom-left]", result.translatedText())
    }

    @Test
    fun aTallBubbleDoesNotPullTheNextRowIntoItsOwn() {
        // "below" overlaps the tall bubble's lower half, but not the row the tall one shares with "beside".
        val result = page(
            bubble("below", 0.3f, 0.5f, height = 0.2f),
            bubble("tall", 0.8f, 0.3f, height = 0.5f),
            bubble("beside", 0.3f, 0.15f, height = 0.2f),
        )
        assertEquals(listOf("tall", "beside", "below"), result.order())
    }

    @Test
    fun aBoxTurnedOnItsSideIsMeasuredAsItLies() {
        // A quarter turn swaps the sides: this "wide" box stands tall on the page and reaches up beside the other.
        val turned = bubble("turned", 0.8f, 0.33f, width = 0.3f, height = 0.05f, rotation = (PI / 2).toFloat())
        val result = page(bubble("upright", 0.2f, 0.2f, height = 0.2f), turned, bubble("low", 0.5f, 0.85f, height = 0.1f))
        assertEquals(listOf("turned", "upright", "low"), result.order())
    }

    @Test
    fun aPageOfHorizontalTextKeepsTheOrderLensGave() {
        val result = page(
            bubble("second", 0.8f, 0.2f, direction = WritingDirection.LeftToRight),
            bubble("first", 0.2f, 0.2f, direction = WritingDirection.LeftToRight),
            bubble("sound effect", 0.5f, 0.6f),
        )
        assertEquals(listOf("second", "first", "sound effect"), result.order())
    }

    @Test
    fun aParagraphWithoutABoxLeavesTheOrderAlone() {
        val loose = bubble("loose", 0.1f, 0.9f).copy(box = null)
        val result = page(bubble("left", 0.2f, 0.2f), loose, bubble("right", 0.8f, 0.2f))
        assertEquals(listOf("left", "loose", "right"), result.order())
    }

    @Test
    fun translationsSkipWhatWasNotTranslatedAndAreNullWhenThereAreNone() {
        val some = page(bubble("left", 0.2f, 0.2f, translated = false), bubble("right", 0.8f, 0.2f))
        assertEquals("[right]", some.translatedText())
        assertNull(page(bubble("a", 0.2f, 0.2f, translated = false), bubble("b", 0.8f, 0.2f, translated = false)).translatedText())
    }
}

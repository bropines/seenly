package io.github.bropines.lens.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import lens.CenterRotatedBox
import lens.CoordinateType
import lens.DeepGleamData
import lens.Geometry
import lens.LensOverlayObjectsResponse
import lens.LensOverlayServerError
import lens.LensOverlayServerResponse
import lens.Text
import lens.TextLayout
import lens.TranslationData
import kotlin.test.Test

class ResponseParsingTest {
    private fun word(text: String, sep: String? = " ") = TextLayout.Word(plain_text = text, text_separator = sep)

    private fun paragraph(vararg lines: List<TextLayout.Word>) =
        TextLayout.Paragraph(lines = lines.map { TextLayout.Line(words = it) })

    private fun success(text: String, vararg lines: TranslationData.Line) = DeepGleamData(
        translation = TranslationData(
            status = TranslationData.Status(code = TranslationData.Status.Code.SUCCESS),
            translation = text,
            target_language = "ru",
            line = lines.toList(),
        ),
    )

    private fun response(paragraphs: List<TextLayout.Paragraph>, gleams: List<DeepGleamData> = emptyList()) =
        LensOverlayServerResponse(
            objects_response = LensOverlayObjectsResponse(
                text = Text(text_layout = TextLayout(paragraphs = paragraphs), content_language = "en"),
                deep_gleams = gleams,
            ),
        )

    @Test
    fun linesJoinWordsWithTheirSeparators() {
        val result = LensResponses.parse(response(listOf(paragraph(listOf(word("Hello"), word("world", null))))), 100, 100)
        result.paragraphs.single().text shouldBe "Hello world"
        result.language shouldBe "en"
    }

    @Test
    fun aFailedTranslationLeavesAHoleInsteadOfShiftingTheRest() {
        val failed = DeepGleamData(
            translation = TranslationData(
                status = TranslationData.Status(code = TranslationData.Status.Code.SAME_LANGUAGE),
                translation = "echo",
            ),
        )
        val result = LensResponses.parse(
            response(
                listOf(paragraph(listOf(word("a"))), paragraph(listOf(word("b"))), paragraph(listOf(word("c")))),
                listOf(success("A"), failed, success("C")),
            ),
            100, 100,
        )
        result.paragraphs.map { it.translation?.text } shouldBe listOf("A", null, "C")
        result.translation shouldBe "A\nC"
    }

    @Test
    fun lineTranslationsComeFromWordRangesInUtf16Units() {
        // "😀 ab cd": the emoji is two UTF-16 units, so "ab" starts at 3.
        val text = "😀 ab cd"
        val first = TranslationData.Line(
            word = listOf(TranslationData.Line.Word(start = 0, end = 2), TranslationData.Line.Word(start = 3, end = 5)),
        )
        val second = TranslationData.Line(word = listOf(TranslationData.Line.Word(start = 6, end = 8)))
        val result = LensResponses.parse(
            response(listOf(paragraph(listOf(word("x")), listOf(word("y")))), listOf(success(text, first, second))),
            100, 100,
        )
        val lines = result.paragraphs.single().lines
        // The gap after a line's last word is read up to the next line's first word.
        lines[0].translation shouldBe "😀 ab "
        lines[1].translation shouldBe "cd"
        (lines[0].translation + lines[1].translation) shouldBe text
    }

    @Test
    fun imageCoordinatesAreNormalisedToTheUploadedSize() {
        val box = Geometry(
            bounding_box = CenterRotatedBox(
                center_x = 320f, center_y = 240f, width = 64f, height = 48f,
                coordinate_type = CoordinateType.IMAGE,
            ),
        )
        val normalised = Geometry(bounding_box = CenterRotatedBox(center_x = 0.5f, center_y = 0.5f, width = 0.1f, height = 0.1f))
        val layout = TextLayout.Paragraph(
            geometry = box,
            lines = listOf(TextLayout.Line(geometry = normalised, words = listOf(word("a")))),
        )
        val result = LensResponses.parse(response(listOf(layout)), 640, 480)
        val paragraphBox = result.paragraphs.single().box!!
        paragraphBox.centerX shouldBe (0.5f plusOrMinus 1e-6f)
        paragraphBox.width shouldBe (0.1f plusOrMinus 1e-6f)
        result.paragraphs.single().lines.single().box!!.centerX shouldBe 0.5f
    }

    @Test
    fun errorTypeZeroIsNotAnError() {
        val ok = response(listOf(paragraph(listOf(word("a"))))).copy(
            error = LensOverlayServerError(error_type = LensOverlayServerError.ErrorType.UNKNOWN_TYPE),
        )
        LensResponses.parse(ok, 1, 1).text shouldBe "a"

        val bad = ok.copy(error = LensOverlayServerError(error_type = LensOverlayServerError.ErrorType.entries.last()))
        shouldThrow<LensException.ServerError> { LensResponses.parse(bad, 1, 1) }
    }

    @Test
    fun garbageIsAMalformedResponse() {
        shouldThrow<LensException.MalformedResponse> { LensResponses.parse(byteArrayOf(0x7f, 0x7f, 0x7f), 1, 1) }
    }
}

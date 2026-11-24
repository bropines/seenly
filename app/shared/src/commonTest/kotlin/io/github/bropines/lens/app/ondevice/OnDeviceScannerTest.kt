package io.github.bropines.lens.app.ondevice

import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDeviceScannerTest {
    private fun paragraph(vararg lines: String, language: String? = null) = Paragraph(
        text = lines.joinToString(" "),
        lines = lines.map { Line(it, emptyList(), null, null, null) },
        box = null,
        language = language,
        writingDirection = WritingDirection.LeftToRight,
        translation = null,
    )

    /** Recognizes [page], "translates" by tagging the text, and has models only for [downloaded]. */
    private class FakeMl(private val page: List<Paragraph>, private val downloaded: Set<String> = setOf("en", "ru", "de")) : OnDeviceMl {
        var script: OcrScript? = null
        val requests = mutableListOf<Triple<String, String, Boolean>>()

        override val translationLanguages = listOf("en", "ru", "de", "ja")
        override suspend fun recognize(image: ByteArray, script: OcrScript): ScanResult {
            this.script = script
            return ScanResult(page, null, 100, 100)
        }

        override suspend fun identifyLanguage(text: String): String? = if (text.startsWith("?")) null else "de"

        override suspend fun translate(texts: List<String>, source: String, target: String, download: Boolean, wifiOnly: Boolean): List<String> {
            requests += Triple(source, target, download)
            if (source !in downloaded && !download) throw OnDeviceException(OnDeviceException.Reason.ModelMissing, "no $source")
            return texts.map { "[$target] $it" }
        }

        override suspend fun models(): List<OnDeviceModel> = emptyList()
        override suspend fun download(model: OnDeviceModel, wifiOnly: Boolean, onProgress: (Float?) -> Unit) {}
        override suspend fun delete(model: OnDeviceModel) {}
    }

    private val settings = AppSettings(targetLanguage = "ru")

    @Test
    fun translatesEachParagraphFromItsOwnLanguageAndSpreadsItOverTheLines() = runTest {
        val ml = FakeMl(listOf(paragraph("Hello there", "world", language = "en"), paragraph("Guten Tag"), paragraph("Привет", language = "ru")))
        val result = OnDeviceScanner(ml).scan(byteArrayOf(), settings, download = false)

        assertEquals(OcrScript.Latin, ml.script)
        // The recognizer named English; the unnamed paragraph was identified as German; Russian needs no translation.
        assertEquals(listOf(Triple("en", "ru", false), Triple("de", "ru", false)), ml.requests)
        val first = result.paragraphs[0]
        assertEquals("[ru] Hello there world", first.translation?.text)
        assertEquals("en", first.translation?.sourceLanguage)
        assertEquals("[ru] Hello there world", first.lines.joinToString("") { it.translation.orEmpty() })
        assertTrue(first.lines.all { !it.translation.isNullOrEmpty() })
        assertEquals("[ru] Guten Tag", result.paragraphs[1].translation?.text)
        assertNull(result.paragraphs[2].translation)
    }

    @Test
    fun theStatedSourceLanguagePicksTheRecognizerAndSkipsDetection() = runTest {
        val ml = FakeMl(listOf(paragraph("こんにちは")), downloaded = setOf("ja"))
        OnDeviceScanner(ml).scan(byteArrayOf(), settings.copy(sourceLanguage = "ja"), download = true)
        assertEquals(OcrScript.Japanese, ml.script)
        assertEquals(listOf(Triple("ja", "ru", true)), ml.requests)
        assertEquals(OcrScript.Korean, ocrScriptFor("ko"))
        assertEquals(OcrScript.Chinese, ocrScriptFor("zh-Hant"))
        assertEquals(OcrScript.Devanagari, ocrScriptFor("hi"))
        assertEquals(OcrScript.Latin, ocrScriptFor(null))
    }

    @Test
    fun aMissingModelFailsTheScanOnlyWhenNothingWasTranslated() = runTest {
        val english = paragraph("Hello", language = "en")
        val japanese = paragraph("Text", language = "ja")
        // One paragraph translated: the page is still worth showing.
        val partial = OnDeviceScanner(FakeMl(listOf(english, japanese))).scan(byteArrayOf(), settings, download = false)
        assertEquals("[ru] Hello", partial.paragraphs[0].translation?.text)
        assertNull(partial.paragraphs[1].translation)

        val error = assertFailsWith<OnDeviceException> { OnDeviceScanner(FakeMl(listOf(japanese))).scan(byteArrayOf(), settings, download = false) }
        assertEquals(OnDeviceException.Reason.ModelMissing, error.reason)
    }

    @Test
    fun textInNoKnownLanguageIsLeftAsItIs() = runTest {
        val ml = FakeMl(listOf(paragraph("?!")))
        val result = OnDeviceScanner(ml).scan(byteArrayOf(), settings, download = false)
        assertTrue(ml.requests.isEmpty())
        assertNull(result.paragraphs.single().translation)
        assertTrue(OnDeviceScanner(FakeMl(emptyList())).scan(byteArrayOf(), settings, download = false).paragraphs.isEmpty())
    }

    @Test
    fun distributesByLineLengthAtWordBreaks() {
        assertEquals(listOf("all of it"), distributeOverLines("all of it", listOf(5)))
        assertEquals(emptyList(), distributeOverLines("text", emptyList()))

        val pieces = distributeOverLines("one two three four five six", listOf(10, 10, 10))
        assertEquals("one two three four five six", pieces.joinToString(""))
        assertEquals(3, pieces.size)
        // Words stay whole, and each piece but the last keeps its trailing space.
        assertEquals(listOf("one two ", "three four ", "five six"), pieces)

        // A long line takes proportionally more.
        assertEquals(listOf("aa ", "bb cc dd ee"), distributeOverLines("aa bb cc dd ee", listOf(2, 10)))
        // Text without spaces breaks between characters.
        assertEquals(listOf("こん", "にちは"), distributeOverLines("こんにちは", listOf(2, 3)))
        // More lines than words: the extra lines are empty, nothing is lost.
        assertEquals("ab", distributeOverLines("ab", listOf(3, 3, 3)).joinToString(""))
    }
}

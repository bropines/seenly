package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.bropines.lens.protocol.BackgroundPatch
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.LineStyle
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ParagraphTranslation
import io.github.bropines.lens.protocol.PatchFormat
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.Word
import io.github.bropines.lens.protocol.WritingDirection
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.test.Test

/** The image formats a platform has to be able to write for these tests. */
enum class TestFormat { Png, Jpeg, Webp }

/**
 * The parts that need a real graphics backend: decoding patches with the
 * platform's codecs and painting a whole overlay with its canvas and fonts.
 *
 * Written once here and run against each backend by a subclass: Skia on the
 * JVM, Android's own graphics (through Robolectric) in the Android host tests.
 */
abstract class RenderContract {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val cyan = 0xFF00FFFF.toInt()

    /** Where text gets its fonts on this platform. */
    protected abstract val fonts: FontFamily.Resolver

    /** Whether the platform can draw any text at all; a bare CI machine may have no fonts. */
    protected abstract val hasFonts: Boolean

    protected abstract fun ImageBitmap.encode(format: TestFormat): ByteArray

    protected abstract fun <T> blocking(block: suspend () -> T): T

    /** Keeps a rendered sheet for a human to look at. */
    protected abstract fun saveSheet(name: String, png: ByteArray)

    /** A file from `src/commonTest/resources/fixtures`. */
    protected abstract fun readFixture(name: String): ByteArray

    private fun solid(width: Int, height: Int, argb: Int): ImageBitmap =
        imageBitmapFromArgb(IntArray(width * height) { argb }, width, height)

    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(1).also { readPixels(it, x, y, 1, 1) }[0]

    private fun pngPatch(argb: Int, horizontalPadding: Float = 0f, verticalPadding: Float = 0f) = BackgroundPatch(
        image = solid(4, 4, argb).encode(TestFormat.Png),
        format = PatchFormat.Png,
        width = 4,
        height = 4,
        verticalPadding = verticalPadding,
        horizontalPadding = horizontalPadding,
    )

    private fun line(box: Box, translation: String?, style: LineStyle?, words: List<Word> = emptyList()) =
        Line(text = "原文", words = words, box = box, translation = translation, translationStyle = style)

    private fun paragraph(
        box: Box?,
        lines: List<Line>,
        target: String = "en",
        source: WritingDirection = WritingDirection.LeftToRight,
        direction: WritingDirection = WritingDirection.LeftToRight,
        alignment: TextAlignment = TextAlignment.Start,
        text: String = lines.joinToString("") { it.translation.orEmpty() },
    ) = Paragraph(
        text = "原文",
        lines = lines,
        box = box,
        language = "ja",
        writingDirection = source,
        translation = ParagraphTranslation(text, "ja", target, direction, alignment, justified = false),
    )

    private fun render(image: ImageBitmap, result: ScanResult, style: OverlayStyle): ImageBitmap {
        val state = blocking { decodeOverlay(result, fonts) }
        return renderTranslatedBitmap(image, result, style, state)
    }

    // ---- decoding ------------------------------------------------------------

    private fun jpegWithMask(mask: ImageBitmap) = BackgroundPatch(
        image = solid(8, 8, red).encode(TestFormat.Jpeg),
        format = PatchFormat.JpegWithPngMask,
        width = 8,
        height = 8,
        verticalPadding = 0f,
        horizontalPadding = 0f,
        textMask = mask.encode(TestFormat.Png),
    )

    private fun assertLeftClearRightRed(decoded: ImageBitmap) {
        decoded.width shouldBe 8
        decoded.height shouldBe 8
        for (y in 0 until 8) {
            (decoded.pixel(1, y) ushr 24) shouldBe 0
            val right = decoded.pixel(6, y)
            (right ushr 24) shouldBe 0xFF
            ((right shr 16) and 0xFF) shouldBeGreaterThan 200
            ((right shr 8) and 0xFF) shouldBeLessThan 60
            (right and 0xFF) shouldBeLessThan 60
        }
    }

    @Test
    fun `a jpeg takes its alpha from the mask's alpha channel`() {
        val mask = imageBitmapFromArgb(IntArray(64) { if (it % 8 < 4) 0x00000000 else black }, 8, 8)
        assertLeftClearRightRed(decodePatch(jpegWithMask(mask))!!)
    }

    @Test
    fun `a jpeg takes its alpha from a greyscale mask's brightness`() {
        val mask = imageBitmapFromArgb(IntArray(64) { if (it % 8 < 4) black else white }, 8, 8)
        assertLeftClearRightRed(decodePatch(jpegWithMask(mask))!!)
    }

    @Test
    fun `a jpeg without a mask stays opaque`() {
        val patch = jpegWithMask(solid(8, 8, black)).copy(textMask = null)
        (decodePatch(patch)!!.pixel(1, 1) ushr 24) shouldBe 0xFF
    }

    @Test
    fun `raw rgba becomes a bitmap directly`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0xFF.toByte(), 0, 0xFF.toByte(),
        )
        val decoded = decodePatch(BackgroundPatch(bytes, PatchFormat.RawRgba, 2, 1, 0f, 0f))!!
        decoded.pixel(0, 0) shouldBe red
        decoded.pixel(1, 0) shouldBe 0xFF00FF00.toInt()
    }

    @Test
    fun `png and webp decode and garbage does not`() {
        decodePatch(pngPatch(cyan))!!.pixel(2, 2) shouldBe cyan
        val webp = pngPatch(cyan).copy(image = solid(4, 4, cyan).encode(TestFormat.Webp), format = PatchFormat.Webp)
        decodePatch(webp)!!.width shouldBe 4
        decodePatch(pngPatch(cyan).copy(image = byteArrayOf(1, 2, 3))) shouldBe null
        decodePatch(BackgroundPatch(ByteArray(3), PatchFormat.RawRgba, 2, 2, 0f, 0f)) shouldBe null
    }

    @Test
    fun `decoding keeps the patches it could read`() {
        val box = Box(0.5f, 0.5f, 0.6f, 0.3f, 0f)
        val good = line(box, "a", LineStyle(black, yellow, pngPatch(cyan)))
        val bad = line(box, "b", LineStyle(black, yellow, pngPatch(cyan).copy(image = byteArrayOf(9))))
        val state = blocking { decodeOverlay(ScanResult(listOf(paragraph(box, listOf(good, bad))), null, 10, 10), fonts) }
        state.patchCount shouldBe 1
        state.patch(0, 0) shouldNotBe null
        state.patch(0, 1) shouldBe null
    }

    // ---- painting ------------------------------------------------------------

    /** One line at x 40..160, y 35..65 of a 200x100 image. */
    private val lineBox = Box(0.5f, 0.5f, 0.6f, 0.3f, 0f)

    @Test
    fun `the patch covers the line box grown by its paddings and nothing else`() {
        val style = LineStyle(black, yellow, pngPatch(cyan, horizontalPadding = 0.4f, verticalPadding = 0.2f))
        val result = ScanResult(listOf(paragraph(lineBox, listOf(line(lineBox, "Hello ", style)))), "ja", 200, 100)
        val source = solid(200, 100, white)
        val out = render(source, result, OverlayStyle())

        out.width shouldBe 200
        out.height shouldBe 100
        // 0.4 and 0.2 of the 30px height: the patch is x 34..166, y 32..68.
        out.pixel(36, 34) shouldBe cyan
        out.pixel(164, 66) shouldBe cyan
        out.pixel(31, 50) shouldBe white
        out.pixel(100, 29) shouldBe white
        out.pixel(169, 50) shouldBe white
        // The source image is not drawn on.
        source.pixel(100, 50) shouldBe white

        if (hasFonts) {
            val ink = (40 until 160).sumOf { x -> (35 until 65).count { y -> out.pixel(x, y).let { it != cyan && it != white } } }
            ink shouldBeGreaterThan 20
        }
    }

    @Test
    fun `a line without a patch gets a flat fill in its background colour`() {
        val result = ScanResult(
            listOf(paragraph(lineBox, listOf(line(lineBox, "i", LineStyle(black, yellow, null))))), "ja", 200, 100,
        )
        val out = render(solid(200, 100, white), result, OverlayStyle())
        out.pixel(150, 60) shouldBe yellow
        out.pixel(30, 50) shouldBe white
    }

    @Test
    fun `fill erasing covers the padded hull`() {
        val result = ScanResult(
            listOf(paragraph(lineBox, listOf(line(lineBox, "i", LineStyle(black, yellow, pngPatch(cyan)))))), "ja", 200, 100,
        )
        val out = render(solid(200, 100, white), result, OverlayStyle(erase = EraseMode.Fill, fillPadding = 0.5f))
        // Half a line height is 15px of padding around x 40..160, y 35..65; the patch is not used.
        out.pixel(150, 60) shouldBe yellow
        out.pixel(30, 50) shouldBe yellow
        out.pixel(100, 24) shouldBe yellow
        out.pixel(20, 50) shouldBe white
        out.pixel(100, 15) shouldBe white
    }

    @Test
    fun `erasing follows the box's rotation`() {
        // 60x20 px turned a quarter turn: it stands at x 90..110, y 20..80.
        val box = Box(0.5f, 0.5f, 0.3f, 0.2f, (PI / 2).toFloat())
        val result = ScanResult(
            listOf(paragraph(box, listOf(line(box, "i", LineStyle(black, yellow, null))))), "ja", 200, 100,
        )
        val out = render(solid(200, 100, white), result, OverlayStyle(erase = EraseMode.Fill, fillPadding = 0f))
        out.pixel(105, 75) shouldBe yellow
        out.pixel(95, 70) shouldBe yellow
        // Inside the box as it would lie unrotated.
        out.pixel(125, 50) shouldBe white
        out.pixel(75, 50) shouldBe white
    }

    @Test
    fun `a missing background colour is sampled from the image`() {
        val result = ScanResult(
            listOf(paragraph(lineBox, listOf(line(lineBox, "i", LineStyle(null, null, null))))), "ja", 200, 100,
        )
        val source = solid(200, 100, 0xFF102040.toInt())
        // The fill is the sampled colour, so the page is unchanged where there is no ink...
        val out = render(source, result, OverlayStyle(erase = EraseMode.Fill))
        out.pixel(150, 60) shouldBe 0xFF102040.toInt()
        // ...and the text, given no colour, is light because the page is dark.
        if (hasFonts) {
            val bright = (40 until 160).sumOf { x -> (35 until 65).count { y -> relativeLuminance(out.pixel(x, y)) > 0.5 } }
            bright shouldBeGreaterThan 0
        }
    }

    @Test
    fun `paragraphs without a translation are left alone`() {
        val untranslated = paragraph(lineBox, listOf(line(lineBox, null, null))).copy(translation = null)
        val out = render(solid(200, 100, white), ScanResult(listOf(untranslated), "ja", 200, 100), OverlayStyle.Manga)
        (0 until 200 step 7).all { x -> (0 until 100 step 7).all { y -> out.pixel(x, y) == white } } shouldBe true
    }

    // ---- layout ----------------------------------------------------------------

    private fun scene(result: ScanResult, style: OverlayStyle, minFontPx: Float = 0f): OverlayScene {
        val state = blocking { decodeOverlay(result, fonts) }
        return buildOverlayScene(state, result, style, solid(result.imageWidth, result.imageHeight, white), minFontPx)
    }

    /** Three 300x30 px lines in a 400x200 image, with far more text than the first can hold. */
    private fun threeLines(): ScanResult {
        val boxes = listOf(0.25f, 0.5f, 0.75f).map { Box(0.5f, it, 0.75f, 0.15f, 0f) }
        val texts = listOf("A line with a great many words in it that cannot fit ", "short ", "end")
        val lines = boxes.zip(texts) { box, text -> line(box, text, LineStyle(black, white, null)) }
        return ScanResult(listOf(paragraph(Box(0.5f, 0.5f, 0.75f, 0.65f, 0f), lines)), "ja", 400, 200)
    }

    @Test
    fun `chromium layout fits each line to its own box`() {
        if (!hasFonts) return
        val texts = scene(threeLines(), OverlayStyle()).texts
        texts.size shouldBe 3
        // Every line fits its 30px-high box, and the crowded one had to shrink further.
        texts.all { it.fontSize < 30f && it.fill.size.height <= 30 && it.fill.size.width <= 300 } shouldBe true
        (texts[0].fontSize < texts[1].fontSize) shouldBe true
        texts.all { it.fill.lineCount == 1 } shouldBe true
    }

    @Test
    fun `reflow sets the paragraph as one block at one size, no larger than the source`() {
        if (!hasFonts) return
        val block = scene(threeLines(), reflow).texts.single()
        (block.fill.lineCount > 1) shouldBe true
        // 130px of paragraph height, 300px of width, and 30px source lines.
        (block.fill.size.height <= 130) shouldBe true
        (block.fontSize <= 27f) shouldBe true
        // One shared size beats the crowded line's own.
        (block.fontSize > scene(threeLines(), OverlayStyle()).texts[0].fontSize) shouldBe true
    }

    @Test
    fun `the readable floor and the text scale override the fit`() {
        if (!hasFonts) return
        val fitted = scene(threeLines(), OverlayStyle()).texts.map { it.fontSize }
        val lineByLine = OverlayStyle(reflowWhenCrowded = false)
        val floored = scene(threeLines(), lineByLine, minFontPx = 40f).texts
        floored.drop(1).map { it.fontSize } shouldBe listOf(40f, 40f)
        // The long first line would be wider than the 400px picture at 40px:
        // the floor gives way to the picture, never below the fit.
        floored[0].fill.size.width shouldBeLessThan 400
        (floored[0].fontSize in fitted[0]..40f) shouldBe true
        scene(threeLines(), OverlayStyle(textScale = 0.5f)).texts.map { it.fontSize } shouldBe
            fitted.map { maxOf(MIN_FONT_PX, it * 0.5f) }
    }

    @Test
    fun `a line raised past its box gets a background sized to the text, inside the image`() {
        if (!hasFonts) return
        // One 300x30 line at the left edge of a 400x200 image, floored at 40px.
        val box = Box(0.375f, 0.5f, 0.75f, 0.15f, 0f)
        val result = ScanResult(
            listOf(paragraph(box, listOf(line(box, "Several words here", LineStyle(black, yellow, null))))),
            "ja", 400, 200,
        )
        val scene = scene(result, OverlayStyle(), minFontPx = 40f)
        val text = scene.texts.single()
        text.fontSize shouldBe 40f
        val grown = scene.erases.filterIsInstance<RectFillOp>().map { it.box }.maxBy { it.width }
        // Measured, not scaled by 40 / fitted: the text plus a little air, and
        // capped at the image.
        (grown.width <= 400f) shouldBe true
        (grown.width >= minOf(400f, text.fill.size.width.toFloat())) shouldBe true
        // Nudged back in: nothing hangs off either side.
        (grown.frame.left >= -0.5f && grown.frame.right <= 400.5f) shouldBe true
    }

    @Test
    fun `a column of short lines that the floor would crowd is re-wrapped as one paragraph`() {
        if (!hasFonts) return
        // Raised to 40px, three lines 50px apart would be written through each other.
        val texts = scene(threeLines(), OverlayStyle(), minFontPx = 40f).texts
        texts.all { it.paragraph == 0 } shouldBe true
        // Every drawn line is clear of the next.
        val spans = texts.flatMap { op ->
            (0 until op.fill.lineCount).map { op.topLeft.y + op.fill.getLineTop(it) to op.topLeft.y + op.fill.getLineBottom(it) }
        }.sortedBy { it.first }
        spans.zipWithNext().all { (a, b) -> b.first >= a.second - 1f } shouldBe true
        // Without a floor it is Chromium's layout, untouched.
        scene(threeLines(), OverlayStyle()).texts.size shouldBe 3
    }

    @Test
    fun `the outline is drawn only when asked for`() {
        if (!hasFonts) return
        scene(threeLines(), OverlayStyle(outline = 0f)).texts.all { it.outline == null } shouldBe true
        scene(threeLines(), OverlayStyle(outline = 2f)).texts.all { it.outline != null } shouldBe true
    }

    /** Two 30x160 px columns of vertical text in a 200x200 image. */
    private fun columns(target: String): ScanResult {
        val boxes = listOf(0.6f, 0.4f).map { Box(it, 0.5f, 0.15f, 0.8f, 0f) }
        val texts = if (target == "ja") listOf("日本語(ab)", "です。") else listOf("Hello ", "there")
        val lines = boxes.zip(texts) { box, text -> line(box, text, LineStyle(black, white, null)) }
        val vertical = WritingDirection.TopToBottom
        return ScanResult(
            listOf(paragraph(Box(0.5f, 0.5f, 0.35f, 0.8f, 0f), lines, target = target, source = vertical, direction = vertical)),
            "ja", 200, 200,
        )
    }

    @Test
    fun `vertical text follows the policy`() {
        if (!hasFonts) return
        // Auto: English is reflowed into one horizontal block...
        val reflowed = scene(columns("en"), OverlayStyle()).texts
        reflowed.size shouldBe 1
        reflowed.single().quarterTurn shouldBe false
        // ...while Japanese stays in columns: upright glyphs, the Latin run on its side.
        val japanese = scene(columns("ja"), OverlayStyle()).texts
        (japanese.size > 2) shouldBe true
        japanese.count { it.quarterTurn } shouldBe 1
        // Keep: English stays in its two columns, lying on its side.
        val kept = scene(columns("en"), OverlayStyle(verticalText = VerticalTextPolicy.Keep)).texts
        kept.size shouldBe 2
        kept.all { it.quarterTurn } shouldBe true
        // Horizontal: even Japanese is reflowed.
        scene(columns("ja"), OverlayStyle(verticalText = VerticalTextPolicy.Horizontal)).texts.size shouldBe 1
    }

    @Test
    fun `a kept column fits inside its box`() {
        if (!hasFonts) return
        val result = columns("ja")
        val texts = scene(result, OverlayStyle()).texts
        // Columns are 30px wide and 160px tall, from y = 20 to y = 180.
        texts.all { it.fontSize <= 30f } shouldBe true
        texts.filter { !it.quarterTurn }.all { it.topLeft.y >= 0f && it.topLeft.y + it.fill.size.height <= 200f } shouldBe true
    }

    /** One paragraph of [text] in a [width] x 240 px box, in a 400x400 image. */
    private fun narrow(text: String, width: Float): ScanResult {
        val box = Box(0.5f, 0.5f, width / 400f, 0.6f, 0f)
        return ScanResult(listOf(paragraph(box, listOf(line(box, text, LineStyle(black, white, null))))), "ja", 400, 400)
    }

    private fun TextOp.lines(): List<String> {
        val text = fill.layoutInput.text.text
        return (0 until fill.lineCount).map { text.substring(fill.getLineStart(it), fill.getLineEnd(it)).trim() }
    }

    // The rectangle: these test wrapping, not the bubble's shape.
    private val reflow = OverlayStyle(layout = TranslationLayout.Reflow, bubbleShape = false)

    @Test
    fun `reflow wraps a long word whole, shrinking the text until it fits`() {
        if (!hasFonts) return
        val text = "Он порекомендовал достопримечательности"
        val block = scene(narrow(text, width = 90f), reflow).texts.single()
        // Each line is one or more whole words: nothing torn, nothing hyphenated.
        block.lines().flatMap { it.split(" ") } shouldBe text.split(" ")
        (block.fill.lineCount >= 2) shouldBe true
        // The longest word fits the 90px box, which is what set the size.
        (block.fill.size.width <= 90) shouldBe true
        (block.fontSize < 12f) shouldBe true
    }

    @Test
    fun `the readable floor gives way before a word is torn`() {
        if (!hasFonts) return
        val text = "Он порекомендовал достопримечательности"
        // A floor of 30px would need some 500px for the longest word; the box has 90.
        val block = scene(narrow(text, width = 90f), reflow, minFontPx = 30f).texts.single()
        block.lines().flatMap { it.split(" ") } shouldBe text.split(" ")
        (block.fontSize < 30f) shouldBe true
        // Where the words do fit, the floor holds.
        (scene(narrow("да и нет но да", width = 90f), reflow, minFontPx = 30f).texts.single().fontSize >= 30f) shouldBe true
    }

    @Test
    fun `a word too wide at any size is broken with hyphens`() {
        if (!hasFonts) return
        val word = "достопримечательности"
        // 12px of width: not even the 3px minimum gets 21 letters across.
        val block = scene(narrow(word, width = 12f), reflow).texts.single()
        block.fontSize shouldBe MIN_FONT_PX
        val lines = block.lines()
        (lines.size >= 2) shouldBe true
        lines.dropLast(1).all { it.endsWith("-") } shouldBe true
        lines.last().endsWith("-") shouldBe false
        // Nothing was lost or added but the hyphens.
        lines.joinToString("") { it.removeSuffix("-") } shouldBe word
    }

    @Test
    fun `text without spaces between words wraps anywhere, unhyphenated`() {
        if (!hasFonts) return
        val text = "ここまで来られるとは思わなかった"
        val horizontal = OverlayStyle(layout = TranslationLayout.Reflow, verticalText = VerticalTextPolicy.Horizontal, bubbleShape = false)
        val block = scene(narrow(text, width = 90f), horizontal).texts.single()
        (block.fill.lineCount >= 2) shouldBe true
        block.lines().joinToString("") shouldBe text
    }

    /**
     * A bubble's main text (two columns, translated) with a short column Lens
     * left untranslated right beside it, its box even overlapping by a few pixels.
     */
    private fun bubbleWithUntranslatedNeighbour(): ScanResult {
        val vertical = WritingDirection.TopToBottom
        val columns = listOf(0.45f, 0.35f).map { Box(it, 0.5f, 0.08f, 0.5f, 0f) }
        val lines = columns.zip(listOf("I never thought ", "we would get this far")) { box, text ->
            line(box, text, LineStyle(black, white, null))
        }
        // x 120..196, y 100..300 in a 400x400 image.
        val translated = paragraph(Box(0.395f, 0.5f, 0.19f, 0.5f, 0f), lines, source = vertical, direction = vertical)
        // x 192..228, y 110..170.
        val leftAlone = paragraph(
            Box(0.525f, 0.35f, 0.09f, 0.15f, 0f),
            listOf(line(Box(0.525f, 0.35f, 0.09f, 0.15f, 0f), null, null)),
            source = vertical,
        ).copy(translation = null)
        return ScanResult(listOf(translated, leftAlone), "ja", 400, 400)
    }

    @Test
    fun `reflowed text never grows over an untranslated neighbour`() {
        if (!hasFonts) return
        val neighbour = Box(0.525f, 0.35f, 0.09f, 0.15f, 0f).toPixels(400f, 400f).bounds()
        for (style in listOf(OverlayStyle.Manga, OverlayStyle(layout = TranslationLayout.Reflow), OverlayStyle())) {
            val scene = scene(bubbleWithUntranslatedNeighbour(), style)
            scene.texts.isEmpty() shouldBe false
            for (block in scene.texts) {
                block.paragraph shouldBe 0
                val text = Rect(block.topLeft, Size(block.fill.size.width.toFloat(), block.fill.size.height.toFloat()))
                text.overlaps(neighbour) shouldBe false
            }
        }
        // It still grows where there is room: to the left, away from the neighbour.
        val grown = scene(bubbleWithUntranslatedNeighbour(), OverlayStyle.Manga).texts
        (grown.minOf { it.topLeft.x } < 120f) shouldBe true
    }

    @Test
    fun `an untranslated paragraph is neither erased nor redrawn`() {
        val result = bubbleWithUntranslatedNeighbour()
        val neighbour = Box(0.525f, 0.35f, 0.09f, 0.15f, 0f).toPixels(400f, 400f).bounds()
        // A page with something to lose where the untranslated column stands.
        val page = imageBitmapFromArgb(
            IntArray(400 * 400) { i -> if (neighbour.contains(Offset((i % 400).toFloat(), (i / 400).toFloat()))) red else white },
            400, 400,
        )
        for (style in listOf(OverlayStyle.Manga, OverlayStyle())) {
            val out = render(page, result, style.copy(minFontSp = 0f))
            // Sampled well inside the column: every pixel is still the original.
            val untouched = (200 until 226 step 3).all { x -> (114 until 168 step 3).all { y -> out.pixel(x, y) == red } }
            untouched shouldBe true
        }
    }

    // ---- gallery -------------------------------------------------------------

    /**
     * Not an assertion but a contact sheet: one page under every preset,
     * handed to [saveSheet] for a human to look at.
     */
    @Test
    fun `write a gallery of every layout`() {
        val width = 900
        val height = 640
        val page = ImageBitmap(width, height)
        val paper = 0xFFF4F1E8.toInt()
        val signBlue = 0xFF1A4FA0.toInt()

        fun tall(cx: Float, cy: Float, w: Float, h: Float) = Box(cx / width, cy / height, w / width, h / height, 0f)
        fun flat(cx: Float, cy: Float, w: Float, h: Float, degrees: Float = 0f) =
            Box(cx / width, cy / height, w / width, h / height, (degrees * PI / 180).toFloat())

        // A sign: three horizontal lines on blue.
        val signLines = listOf(
            flat(220f, 70f, 300f, 44f) to "Центральный вокзал ",
            flat(220f, 120f, 300f, 30f) to "Выход к автобусам и такси, касса на втором этаже ",
            flat(220f, 158f, 180f, 26f) to "Открыто 24 часа",
        )
        // A tilted caption.
        val tilted = flat(640f, 110f, 380f, 40f, degrees = -12f) to "A caption that follows the angle of the original"
        // A speech bubble: three vertical columns, read right to left.
        val bubble = listOf(
            tall(300f, 400f, 34f, 220f) to "I never thought ",
            tall(250f, 400f, 34f, 220f) to "we would make it this far, ",
            tall(200f, 380f, 34f, 180f) to "did you?",
        )
        // A second bubble, translated into Japanese: stays vertical under Auto.
        val japanese = listOf(
            tall(690f, 400f, 40f, 240f) to "ここまで来られるとは、",
            tall(640f, 400f, 40f, 240f) to "思わなかった（ABC）ー。",
        )
        // Right to left.
        val arabic = flat(450f, 590f, 420f, 44f) to "مرحبا بكم في المحطة المركزية"

        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(page), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color(paper))
            drawRect(Color(signBlue), Offset(40f, 30f), Size(360f, 160f))
            drawOval(Color.White, Offset(120f, 250f), Size(260f, 300f))
            drawOval(Color.Black, Offset(120f, 250f), Size(260f, 300f), style = Stroke(3f))
            drawOval(Color.White, Offset(560f, 250f), Size(220f, 300f))
            drawOval(Color.Black, Offset(560f, 250f), Size(220f, 300f), style = Stroke(3f))
            // Stand-ins for the source glyphs, so erasing has something to erase.
            fun scribble(box: Box, color: Color) {
                val px = box.toPixels(width.toFloat(), height.toFloat())
                rotate(px.rotationDegrees, px.center) {
                    val frame = px.frame.deflate(3f)
                    drawRect(color, frame.topLeft, frame.size, alpha = 0.55f)
                }
            }
            signLines.forEach { scribble(it.first, Color.White) }
            scribble(tilted.first, Color.Black)
            (bubble + japanese).forEach { scribble(it.first, Color.Black) }
            scribble(arabic.first, Color.Black)
        }

        fun bounding(boxes: List<Box>): Box {
            val l = boxes.minOf { it.left }
            val t = boxes.minOf { it.top }
            val r = boxes.maxOf { it.right }
            val b = boxes.maxOf { it.bottom }
            return Box((l + r) / 2, (t + b) / 2, r - l, b - t, 0f)
        }
        fun lines(items: List<Pair<Box, String>>, text: Int, background: Int, patch: Int?) = items.map { (box, s) ->
            // Words without boxes of their own, so the sheet also shows the line being split among them.
            val words = s.trim().split(" ").map { Word(it, " ", null) }
            line(box, s, LineStyle(text, background, patch?.let { pngPatch(it, horizontalPadding = 0.4f, verticalPadding = 0.2f) }), words)
        }

        val result = ScanResult(
            paragraphs = listOf(
                paragraph(bounding(signLines.map { it.first }), lines(signLines, white, signBlue, signBlue), target = "ru"),
                paragraph(tilted.first, lines(listOf(tilted), black, paper, paper)),
                paragraph(
                    bounding(bubble.map { it.first }), lines(bubble, black, white, white),
                    source = WritingDirection.TopToBottom, direction = WritingDirection.TopToBottom,
                ),
                paragraph(
                    bounding(japanese.map { it.first }), lines(japanese, black, white, white),
                    target = "ja", source = WritingDirection.TopToBottom, direction = WritingDirection.TopToBottom,
                ),
                paragraph(arabic.first, lines(listOf(arabic), black, paper, paper), target = "ar", direction = WritingDirection.RightToLeft),
            ),
            language = "ja",
            imageWidth = width,
            imageHeight = height,
        )

        val sheets = mapOf(
            "0-source" to null,
            "1-chromium" to OverlayStyle.Chromium,
            "2-chromium-keep-vertical" to OverlayStyle(verticalText = VerticalTextPolicy.Keep),
            "3-reflow" to OverlayStyle(layout = TranslationLayout.Reflow),
            "4-manga" to OverlayStyle.Manga,
        )
        for ((name, style) in sheets) {
            val sheet = if (style == null) page else render(page, result, style)
            sheet.width shouldBe width
            saveSheet(name, sheet.encode(TestFormat.Png))
        }
        // TextBoxes mode, through the same drawing code the composable uses.
        val boxes = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(boxes), Size(width.toFloat(), height.toFloat())) {
            drawImage(page)
            // The sign's second line from its third word on, and all of its third line.
            val selected = WordLayout(result, width.toFloat(), height.toFloat()).lineSelections(WordRange(4, 13))
            drawTextMode(result, width.toFloat(), height.toFloat(), OverlayStyle().highlightColor, dp = 1f, selection = selected)
        }
        saveSheet("5-text-boxes", boxes.encode(TestFormat.Png))
    }

    // ---- real pages ------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScanResult =
        json.decodeFromString(ScanResult.serializer(), readFixture("$name.json").decodeToString())

    /**
     * The readable floor in image pixels for [image] shown fitted in a phone's
     * 392 x 760 dp viewer, as [TranslatedImage] computes it (sp == dp here).
     */
    private fun phoneFloor(image: ImageBitmap, style: OverlayStyle): Float {
        val scale = minOf(392f / image.width, 760f / image.height)
        return style.minFontSp / scale
    }

    /** Roughly what this renderer did before the userscript's techniques: no floor, no rescue, no shape. */
    private fun before(style: OverlayStyle) = style.copy(minFontSp = 0f, reflowWhenCrowded = false, bubbleShape = false)

    private class Page(val name: String, val image: String, val answer: String, val style: OverlayStyle)

    private val pages = listOf(
        Page("manga-chromium", "manga1.png", "manga1.ru", OverlayStyle.Chromium),
        Page("manga-manga", "manga1.png", "manga1.ru", OverlayStyle.Manga),
        Page("phone-ru-chromium", "tall1.jpg", "tall1.ru", OverlayStyle.Chromium),
        Page("phone-ru-manga", "tall1.jpg", "tall1.ru", OverlayStyle.Manga),
        Page("phone-en-chromium", "tall1.jpg", "tall1.en", OverlayStyle.Chromium),
        Page("ui-en-chromium", "s1.jpg", "s1.en", OverlayStyle.Chromium),
    )

    /**
     * Pixels inked by two paragraphs at once: every paragraph's text is drawn
     * alone, and a pixel any two of those drawings touch is counted. Ink is
     * fill and outline both.
     */
    private fun overlap(scene: OverlayScene): Int {
        val w = scene.imageWidth
        val h = scene.imageHeight
        val owners = IntArray(w * h) { -1 }
        val pixels = IntArray(w * h)
        var shared = 0
        for ((paragraph, ops) in scene.texts.groupBy { it.paragraph }) {
            val canvas = ImageBitmap(w, h)
            val alone = OverlayScene(scene.source, w, h, emptyList(), ops)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(w.toFloat(), h.toFloat())) {
                drawOverlayScene(alone)
            }
            canvas.readPixels(pixels)
            for (i in pixels.indices) {
                if ((pixels[i] ushr 24) < 64) continue
                val owner = owners[i]
                if (owner == -1) owners[i] = paragraph else if (owner != paragraph) shared++
            }
        }
        return shared
    }

    @Test
    fun `real pages render, and their paragraphs are never written through each other`() {
        if (!hasFonts) return
        val report = StringBuilder()
        for (page in pages) {
            val image = decodeEncodedImage(readFixture(page.image))!!
            val result = fixture(page.answer)
            val state = blocking { decodeOverlay(result, fonts) }
            for ((label, style) in listOf("before" to before(page.style), "after" to page.style)) {
                val floor = phoneFloor(image, style)
                val scene = buildOverlayScene(state, result, style, image, floor)
                val shared = overlap(scene)
                report.append("${page.name} $label: floor ${floor.roundToInt()}px, ${scene.texts.size} ops, overlap $shared\n")
                val out = renderTranslatedBitmap(image, result, style, state, floor)
                saveSheet("page-${page.name}-$label", out.encode(TestFormat.Png))
                // The bubbles of a manga page stay apart, whichever preset draws them.
                if (label == "after" && page.image == "manga1.png") shared shouldBe 0
            }
        }
        println(report)
    }

    @Test
    fun `text re-set in a drawn bubble follows its outline`() {
        if (!hasFonts) return
        // A white ellipse with a black outline on grey, x 100..300, y 60..340,
        // and a column of three short lines in its middle.
        val width = 400
        val height = 400
        val page = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(page), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color(0xFF808080))
            drawOval(Color.White, Offset(100f, 60f), Size(200f, 280f))
            drawOval(Color.Black, Offset(100f, 60f), Size(200f, 280f), style = Stroke(4f))
        }
        val boxes = listOf(170f, 200f, 230f).map { Box(0.5f, it / height, 60f / width, 24f / height, 0f) }
        val lines = boxes.map { line(it, "x", LineStyle(black, white, null)) }
        val text = "This is a rather long translation of a short line of speech"
        val result = ScanResult(
            listOf(paragraph(Box(0.5f, 0.5f, 60f / width, 84f / height, 0f), lines, text = text, alignment = TextAlignment.Center)),
            "ja", width, height,
        )
        val state = blocking { decodeOverlay(result, fonts) }
        val texts = buildOverlayScene(state, result, OverlayStyle(layout = TranslationLayout.Reflow), page, 0f).texts
        // One op per line, every word kept, and each line inside the ellipse.
        (texts.size >= 3) shouldBe true
        texts.joinToString(" ") { it.fill.layoutInput.text.text } shouldBe text
        for (op in texts) {
            val top = op.topLeft.y
            val bottom = top + op.fill.size.height
            val mid = (top + bottom) / 2
            val half = 100f * kotlin.math.sqrt(1f - ((mid - 200f) / 140f).let { it * it })
            (op.topLeft.x >= 200f - half - 1f) shouldBe true
            (op.topLeft.x + op.fill.multiParagraph.maxIntrinsicWidth <= 200f + half + 1f) shouldBe true
        }
        // Larger than the same text forced into the 60px paragraph box.
        val boxed = scene(result, OverlayStyle(layout = TranslationLayout.Reflow, bubbleShape = false)).texts.single()
        (texts.first().fontSize > boxed.fontSize) shouldBe true
    }
}

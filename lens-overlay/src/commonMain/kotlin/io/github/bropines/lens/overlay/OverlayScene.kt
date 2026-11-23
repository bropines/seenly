package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.WritingDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The overlay is laid out once, in the image's own pixels, into a flat list of
// drawing operations. The composable replays that list under its fit-and-zoom
// transform, and the exporter replays it at full resolution: both therefore
// draw exactly the same thing, and zooming never re-measures any text.

/** Something that removes source text. Drawn before any translated text. */
internal sealed interface EraseOp

/** The server's inpainted background, stretched over [box]. */
internal class PatchOp(val box: OrientedBox, val image: ImageBitmap) : EraseOp

/** A flat colour over [box], for a line that has no usable patch. */
internal class RectFillOp(val box: OrientedBox, val color: Color) : EraseOp

/** A flat colour over a paragraph's hull, grown by [padding] pixels with round corners. */
internal class HullFillOp(val path: Path, val color: Color, val padding: Float) : EraseOp

/**
 * One piece of laid-out text.
 *
 * It is drawn inside `rotate(rotationDegrees, pivot)`, which is the frame of
 * the box it belongs to, with its top-left corner at [topLeft]. With
 * [quarterTurn] it is turned a further 90 degrees clockwise about [topLeft],
 * for text that lies on its side in a vertical column.
 *
 * Colours and the outline's stroke are baked into the layouts: changing a
 * paragraph's paint between draws makes Skia lay it out again, every frame.
 */
internal class TextOp(
    val paragraph: Int,
    val pivot: Offset,
    val rotationDegrees: Float,
    val topLeft: Offset,
    val quarterTurn: Boolean,
    val fill: TextLayoutResult,
    val outline: TextLayoutResult?,
    val fontSize: Float,
)

/** Everything [OverlayMode.Translated] draws over an image, in that image's pixels. */
internal class OverlayScene(
    val source: OverlayState,
    val imageWidth: Int,
    val imageHeight: Int,
    val erases: List<EraseOp>,
    val texts: List<TextOp>,
)

/** Chromium's `getOutlineStyleForLine()`: an outline of this fraction of the font size. */
private const val OUTLINE_RATIO = 0.02f

/** An upright glyph's cell in a vertical column, in font sizes. */
private const val VERTICAL_CELL = 1.05f

/** A font's size as a fraction of the thickness of a line set in it. */
private const val SOURCE_SIZE_RATIO = 0.9f

/**
 * Lays [result] out over an image of [image]'s size.
 *
 * Text is measured here, which is the slow part: call it off the main thread.
 *
 * @param state the decoded patches; normally decoded from [result] itself.
 * @param minFontPx the readable-size floor in image pixels, or 0 for none.
 */
internal fun buildOverlayScene(
    state: OverlayState,
    result: ScanResult,
    style: OverlayStyle,
    image: ImageBitmap,
    minFontPx: Float,
): OverlayScene = SceneBuilder(state, result, style, image, minFontPx).build()

private enum class Side { Left, Center, Right }

private fun TextAlignment.side(rtl: Boolean): Side = when (this) {
    TextAlignment.Start -> if (rtl) Side.Right else Side.Left
    TextAlignment.End -> if (rtl) Side.Left else Side.Right
    TextAlignment.Center -> Side.Center
}

private fun opaque(argb: Int): Int = argb or ARGB_BLACK

private class SceneBuilder(
    private val state: OverlayState,
    private val result: ScanResult,
    private val style: OverlayStyle,
    private val image: ImageBitmap,
    private val minFontPx: Float,
) {
    private val width = image.width.toFloat()
    private val height = image.height.toFloat()

    // Density 1 makes one sp one image pixel. No cache: nothing is measured twice.
    // The locale is only a fallback for paragraphs whose target language is
    // unknown, read once per layout: there is nothing here to observe it with.
    @Suppress("DEPRECATION")
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = state.fontFamilyResolver,
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
        defaultLocaleList = LocaleList.current,
        cacheSize = 0,
    )

    /** The locale of the paragraph being laid out: it picks the regional face for CJK text. */
    private var locale: LocaleList? = null

    private val erases = ArrayList<EraseOp>()
    private val texts = ArrayList<TextOp>()

    private val paragraphBounds: List<Rect?> =
        result.paragraphs.map { it.box?.toPixels(width, height)?.bounds() }

    /** Only made when a colour has to be sampled; on Android it may copy the image. */
    private val readable: ImageBitmap by lazy { image.forSoftwareCanvas() }

    fun build(): OverlayScene {
        result.paragraphs.forEachIndexed(::paragraph)
        return OverlayScene(state, image.width, image.height, erases, texts)
    }

    private fun paragraph(index: Int, paragraph: Paragraph) {
        val translation = paragraph.translation ?: return
        val usable: (OrientedBox) -> Boolean = { it.width >= 1f && it.height >= 1f }
        val lineBoxes = paragraph.lines.map { it.box?.toPixels(width, height)?.takeIf(usable) }
        val paragraphBox = paragraph.box?.toPixels(width, height)?.takeIf(usable)

        // The source's direction counts too: its line boxes are tall columns
        // whatever direction the translation claims for itself.
        val columns = translation.writingDirection == WritingDirection.TopToBottom ||
            paragraph.writingDirection == WritingDirection.TopToBottom
        val vertical = columns && keepsVertical(style.verticalText, translation.targetLanguage)
        val hasLineText = paragraph.lines.indices.any {
            lineBoxes[it] != null && !paragraph.lines[it].translation.isNullOrBlank()
        }
        if (!hasLineText && paragraphBox == null) return
        // Horizontal text cannot sensibly occupy a tall column, and a paragraph
        // whose lines carry no text of their own can only be set as a whole.
        val reflow = paragraphBox != null && !vertical &&
            (columns || style.layout == TranslationLayout.Reflow || !hasLineText)

        val rtl = translation.writingDirection == WritingDirection.RightToLeft ||
            isRtlLanguage(translation.targetLanguage)
        val alignment = style.alignment ?: translation.alignment
        locale = translation.targetLanguage?.takeIf { it.isNotBlank() }?.let { tag ->
            try {
                LocaleList(tag)
            } catch (e: Exception) {
                null
            }
        }

        val paragraphBackground: Int by lazy {
            paragraph.lines.firstNotNullOfOrNull { it.translationStyle?.backgroundColor }?.let(::opaque)
                ?: (paragraphBox ?: lineBoxes.firstNotNullOfOrNull { it })?.let(::sampleAround)
                ?: ARGB_WHITE
        }
        fun background(line: Int): Int =
            paragraph.lines[line].translationStyle?.backgroundColor?.let(::opaque) ?: paragraphBackground

        when (style.erase) {
            EraseMode.Fill -> eraseHull(lineBoxes.filterNotNull().ifEmpty { listOfNotNull(paragraphBox) }, paragraphBackground)
            EraseMode.ServerPatch -> paragraph.lines.forEachIndexed { l, line ->
                val box = lineBoxes[l] ?: return@forEachIndexed
                val patch = line.translationStyle?.background
                val bitmap = state.patch(index, l)
                if (patch != null && bitmap != null) {
                    erases += PatchOp(patchBox(box, patch.horizontalPadding, patch.verticalPadding), bitmap)
                } else if (reflow || !line.translation.isNullOrBlank()) {
                    erases += RectFillOp(box.inflate(2f, 1f), Color(background(l)))
                }
            }
        }

        if (reflow) {
            val text = translation.text.trim()
            if (text.isEmpty()) return
            val styled = paragraph.lines.firstOrNull { it.translationStyle?.textColor != null }?.translationStyle
            val ink = Ink(resolveTextColor(styled?.textColor, paragraphBackground), paragraphBackground)
            // A line is about as thick as its glyphs are tall: across a column, down a row.
            val thickest = lineBoxes.filterNotNull().maxOfOrNull { if (columns) it.width else it.height }
            val sizeCap = thickest?.let { it * SOURCE_SIZE_RATIO } ?: Float.MAX_VALUE
            reflowBlock(index, paragraphBox, text, rtl, alignment, ink, sizeCap)
        } else {
            paragraph.lines.forEachIndexed { l, line ->
                val box = lineBoxes[l] ?: return@forEachIndexed
                val text = line.translation?.trim().orEmpty()
                if (text.isEmpty()) return@forEachIndexed
                val ink = Ink(resolveTextColor(line.translationStyle?.textColor, background(l)), background(l))
                if (vertical) column(index, box, text, alignment, ink) else horizontalLine(index, box, text, rtl, alignment, ink)
            }
        }
    }

    /** A text colour and the background it sits on, which is also its outline. */
    private class Ink(val text: Int, val background: Int)

    // ---- erasing -----------------------------------------------------------

    private fun eraseHull(boxes: List<OrientedBox>, color: Int) {
        val hull = convexHull(boxes.flatMap { it.corners() })
        if (hull.size < 3) return
        val thinnest = boxes.minOf { min(it.width, it.height) }
        val path = Path().apply {
            moveTo(hull[0].x, hull[0].y)
            for (i in 1 until hull.size) lineTo(hull[i].x, hull[i].y)
            close()
        }
        erases += HullFillOp(path, Color(color), max(0f, thinnest * style.fillPadding))
    }

    /** The median colour just outside [box], or null when the image cannot be read. */
    private fun sampleAround(box: OrientedBox): Int? = try {
        val pad = max(2f, 0.15f * min(box.width, box.height))
        val corners = box.inflate(pad, pad).corners()
        val perEdge = 8
        val samples = IntArray(4 * perEdge)
        val pixel = IntArray(1)
        var count = 0
        for (edge in 0 until 4) {
            val a = corners[edge]
            val b = corners[(edge + 1) % 4]
            for (i in 0 until perEdge) {
                val t = (i + 0.5f) / perEdge
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x < 0 || y < 0 || x >= image.width || y >= image.height) continue
                readable.readPixels(pixel, x, y, 1, 1)
                samples[count++] = pixel[0]
            }
        }
        medianColor(samples.copyOf(count))
    } catch (e: Exception) {
        null
    }

    // ---- measuring ---------------------------------------------------------

    private fun textStyle(size: Float, rtl: Boolean, color: Color, align: TextAlign, outlineWidth: Float) = TextStyle(
        color = color,
        fontSize = size.sp,
        fontFamily = style.fontFamily,
        localeList = locale,
        textAlign = align,
        textDirection = if (rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
        lineBreak = LineBreak.Paragraph,
        // Linear metrics and subpixel positions. The text is laid out in image
        // pixels and drawn under the view's zoom, so advances rounded to whole
        // pixels at a 9px size would show as uneven spacing once magnified, and
        // would make width jump, not grow, with the font size.
        textMotion = TextMotion.Animated,
        drawStyle = if (outlineWidth > 0f) {
            Stroke(outlineWidth, join = StrokeJoin.Round, cap = StrokeCap.Round)
        } else {
            null
        },
    )

    /**
     * Lays [text] out at [size]: on one line, or wrapped to [wrapWidth] when
     * that is given. Overflow stays visible, because a size raised by the
     * readable-size floor is allowed to leave its box.
     */
    private fun measure(
        text: String,
        size: Float,
        rtl: Boolean,
        color: Color = Color.Black,
        wrapWidth: Float? = null,
        align: TextAlign = TextAlign.Start,
        outlineWidth: Float = 0f,
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = textStyle(size, rtl, color, align, outlineWidth),
        overflow = TextOverflow.Visible,
        softWrap = wrapWidth != null,
        maxLines = if (wrapWidth != null) Int.MAX_VALUE else 1,
        constraints = if (wrapWidth != null) Constraints.fixedWidth(max(1, ceil(wrapWidth).toInt())) else Constraints(),
    )

    /**
     * The stroke width of the outline at [size], or 0 for none. A stroke
     * straddles the glyph's edge, so it is twice the visible outline. No
     * outline is drawn when it would not differ from the text itself.
     */
    private fun outlineWidth(size: Float, ink: Ink): Float =
        if (style.outline > 0f && contrastRatio(ink.text, ink.background) >= 1.5) {
            size * OUTLINE_RATIO * style.outline * 2f
        } else {
            0f
        }

    private fun add(
        paragraph: Int,
        box: OrientedBox,
        topLeft: Offset,
        size: Float,
        ink: Ink,
        quarterTurn: Boolean = false,
        layout: (color: Color, outlineWidth: Float) -> TextLayoutResult,
    ) {
        val stroke = outlineWidth(size, ink)
        texts += TextOp(
            paragraph = paragraph,
            pivot = box.center,
            rotationDegrees = box.rotationDegrees,
            topLeft = topLeft,
            quarterTurn = quarterTurn,
            fill = layout(Color(ink.text), 0f),
            outline = if (stroke > 0f) layout(Color(ink.background), stroke) else null,
            fontSize = size,
        )
    }

    private val OrientedBox.upright: Boolean get() = abs(rotation) < 0.01f

    // ---- Chromium layout: one line in its own box ---------------------------

    private fun horizontalLine(paragraph: Int, box: OrientedBox, text: String, rtl: Boolean, alignment: TextAlignment, ink: Ink) {
        // calculateFontSizePixels(): the height test uses the font's line box,
        // not these glyphs' ink, and "equal" already fails, which leaves slack.
        val fitted = fitFontSize(MIN_FONT_PX, max(MIN_FONT_PX, box.height)) { size ->
            val probe = measure(text, size, rtl)
            probe.size.width < box.width && probe.size.height < box.height
        }
        val size = finalFontSize(fitted, style.textScale, minFontPx)
        val probe = measure(text, size, rtl)
        val textWidth = probe.size.width.toFloat()
        val frame = box.frame
        var x = when {
            // Text the floor has pushed past its box spills evenly, as it grows about the centre.
            textWidth > box.width -> box.centerX - textWidth / 2
            else -> when (alignment.side(rtl)) {
                Side.Left -> frame.left
                Side.Right -> frame.right - textWidth
                Side.Center -> box.centerX - textWidth / 2
            }
        }
        // A whole sentence a few pixels off its place beats half a sentence in it.
        if (box.upright && textWidth <= width) x = x.coerceIn(0f, width - textWidth)
        val y = box.centerY - probe.size.height / 2f
        add(paragraph, box, Offset(x, y), size, ink) { color, outline ->
            measure(text, size, rtl, color, outlineWidth = outline)
        }
    }

    // ---- Reflow layout: the paragraph wrapped into its box ------------------

    /**
     * Sets the whole translation as one wrapped block.
     *
     * @param sizeCap the largest font size allowed: the size of the source's own
     *   glyphs. A short translation would otherwise fill a big bubble with
     *   text several times larger than what it replaces.
     */
    private fun reflowBlock(
        paragraph: Int,
        box: OrientedBox,
        text: String,
        rtl: Boolean,
        alignment: TextAlignment,
        ink: Ink,
        sizeCap: Float,
    ) {
        val align = when (alignment) {
            TextAlignment.Start -> TextAlign.Start
            TextAlignment.End -> TextAlign.End
            TextAlignment.Center -> TextAlign.Center
        }
        fun fit(area: OrientedBox): Float =
            fitFontSize(MIN_FONT_PX, max(MIN_FONT_PX, min(area.height, sizeCap))) { size ->
                val probe = measure(text, size, rtl, wrapWidth = area.width, align = align)
                // The widest unbreakable word has to fit as well: text is shrunk
                // before a word is ever split across lines.
                probe.size.height <= area.height && probe.multiParagraph.minIntrinsicWidth <= area.width + 0.5f
            }

        // Grow only as far as the text needs: a paragraph that already reads
        // at the source's size stays exactly where the source was.
        val growth = style.reflowGrowth
        var area = box
        var fitted = fit(box)
        if (growth > 1f) {
            for (step in listOf(1f + (growth - 1f) / 2, growth)) {
                if (fitted >= sizeCap * 0.9f) break
                val candidate = reflowArea(paragraph, box, step)
                val candidateFit = fit(candidate)
                if (candidateFit > fitted) {
                    area = candidate
                    fitted = candidateFit
                }
            }
        }
        val size = finalFontSize(fitted, style.textScale, minFontPx)
        val probe = measure(text, size, rtl, wrapWidth = area.width, align = align)
        val x = area.centerX - probe.size.width / 2f
        var y = area.centerY - probe.size.height / 2f
        if (area.upright && probe.size.height <= height) y = y.coerceIn(0f, height - probe.size.height)
        add(paragraph, area, Offset(x, y), size, ink) { color, outline ->
            measure(text, size, rtl, color, wrapWidth = area.width, align = align, outlineWidth = outline)
        }
    }

    /**
     * The area a reflowed paragraph may use: its box, grown by [growth] (up to
     * [OverlayStyle.reflowGrowth]) where neighbours and the image leave room.
     * Height grows less than width (at most a fifth): bubbles are stacked more
     * tightly than they are packed side by side. A visibly rotated box is left
     * alone, since room is measured along the image's axes.
     */
    private fun reflowArea(paragraph: Int, box: OrientedBox, growth: Float): OrientedBox {
        if (growth <= 1f || abs(box.rotation) > 0.05f) return box
        val own = box.frame
        val others = paragraphBounds.filterIndexed { i, rect -> i != paragraph && rect != null }.filterNotNull()
        val room = roomFor(own, others, growth, width, height)
        val growY = own.height * (min(growth, 1.2f) - 1f) / 2
        val top = max(room.top, own.top - growY)
        val bottom = min(room.bottom, own.bottom + growY)
        return OrientedBox((room.left + room.right) / 2, (top + bottom) / 2, room.width, bottom - top, box.rotation)
    }

    // ---- vertical columns --------------------------------------------------

    private enum class CellKind { Upright, Corner, Turned, Sideways, Space }

    /** One step down a column: [advance] along it, [thickness] across it. */
    private class Cell(val kind: CellKind, val text: String, val advance: Float, val thickness: Float, val probe: TextLayoutResult)

    /**
     * CSS `writing-mode: vertical-rl` with `text-orientation: mixed`: CJK
     * glyphs stand upright and stack, other runs lie on their side, turned a
     * quarter turn clockwise.
     */
    private fun cells(runs: List<VerticalRun>, size: Float): List<Cell> = buildList {
        for (run in runs) {
            if (!run.upright) {
                val probe = measure(run.text, size, rtl = false)
                add(Cell(CellKind.Sideways, run.text, probe.size.width.toFloat(), probe.size.height.toFloat(), probe))
                continue
            }
            for (glyph in codePointStrings(run.text)) {
                val probe = measure(glyph, size, rtl = false)
                val kind = when {
                    glyph.isBlank() -> CellKind.Space
                    glyph in VERTICAL_ROTATED_PUNCTUATION -> CellKind.Turned
                    glyph in VERTICAL_CORNER_PUNCTUATION -> CellKind.Corner
                    else -> CellKind.Upright
                }
                val advance = if (kind == CellKind.Space) probe.size.width.toFloat() else size * VERTICAL_CELL
                add(Cell(kind, glyph, advance, max(size, probe.size.width.toFloat()), probe))
            }
        }
    }

    private fun column(paragraph: Int, box: OrientedBox, text: String, alignment: TextAlignment, ink: Ink) {
        val runs = verticalRuns(text)
        // A column's extent is proportional to the font size, so one
        // measurement at a reference size gives the fit without a search.
        val reference = 32f
        val probeCells = cells(runs, reference)
        val across = probeCells.maxOfOrNull { it.thickness } ?: return
        val along = probeCells.sumOf { it.advance.toDouble() }.toFloat()
        if (across <= 0f || along <= 0f) return
        val exact = reference * min(box.width / across, box.height / along) * 0.98f
        val fitted = max(MIN_FONT_PX, floor(exact / FONT_STEP_PX) * FONT_STEP_PX)
        val size = finalFontSize(fitted, style.textScale, minFontPx)

        val laidOut = cells(runs, size)
        val total = laidOut.sumOf { it.advance.toDouble() }.toFloat()
        val frame = box.frame
        var y = when {
            total > box.height -> box.centerY - total / 2
            else -> when (alignment) {
                TextAlignment.Start -> frame.top
                TextAlignment.End -> frame.bottom - total
                TextAlignment.Center -> box.centerY - total / 2
            }
        }
        val cx = box.centerX
        for (cell in laidOut) {
            val w = cell.probe.size.width.toFloat()
            val h = cell.probe.size.height.toFloat()
            // A quarter turn about the top-left corner O sends the layout's box
            // to x in [O.x - h, O.x] and y in [O.y, O.y + w].
            val (topLeft, turned) = when (cell.kind) {
                CellKind.Space -> null to false
                CellKind.Upright -> Offset(cx - w / 2, y + (cell.advance - h) / 2) to false
                // Kuten and touten hang in the upper right of the cell.
                CellKind.Corner -> Offset(cx - w / 2 + size * 0.5f, y + (cell.advance - h) / 2 - size * 0.5f) to false
                CellKind.Turned -> Offset(cx + h / 2, y + (cell.advance - w) / 2) to true
                CellKind.Sideways -> Offset(cx + h / 2, y) to true
            }
            if (topLeft != null) {
                add(paragraph, box, topLeft, size, ink, quarterTurn = turned) { color, outline ->
                    measure(cell.text, size, rtl = false, color = color, outlineWidth = outline)
                }
            }
            y += cell.advance
        }
    }
}

// ---- drawing ---------------------------------------------------------------

/** Draws [scene]. The scope's coordinates must be the image's pixels. */
internal fun DrawScope.drawOverlayScene(scene: OverlayScene) {
    for (op in scene.erases) {
        when (op) {
            is PatchOp -> rotate(op.box.rotationDegrees, op.box.center) {
                val frame = op.box.frame
                withTransform({
                    translate(frame.left, frame.top)
                    scale(frame.width / op.image.width, frame.height / op.image.height, Offset.Zero)
                }) {
                    // Bilinear: the patch is small and heavily compressed, and
                    // sharper filters ring around the erased glyphs.
                    drawImage(op.image, filterQuality = FilterQuality.Low)
                }
            }

            is RectFillOp -> rotate(op.box.rotationDegrees, op.box.center) {
                drawRect(op.color, op.box.frame.topLeft, op.box.frame.size)
            }

            is HullFillOp -> {
                drawPath(op.path, op.color)
                if (op.padding > 0f) {
                    // A round-jointed stroke grows the shape by the padding in
                    // every direction and rounds its corners in one step.
                    drawPath(op.path, op.color, style = Stroke(op.padding * 2, join = StrokeJoin.Round))
                }
            }
        }
    }
    // All text after all erasing: text that leaves its box must not be wiped
    // out by the next paragraph's patch.
    for (op in scene.texts) {
        rotate(op.rotationDegrees, op.pivot) {
            if (op.quarterTurn) rotate(90f, op.topLeft) { drawTextOp(op) } else drawTextOp(op)
        }
    }
}

private fun DrawScope.drawTextOp(op: TextOp) {
    op.outline?.let { drawText(it, topLeft = op.topLeft) }
    drawText(op.fill, topLeft = op.topLeft)
}

/**
 * Draws [OverlayMode.TextBoxes]: a tinted, outlined box per paragraph and a
 * lighter tint per line. The scope's coordinates must be the image's pixels;
 * [strokeWidth] is in those pixels too, so pass one that undoes the zoom.
 */
internal fun DrawScope.drawTextBoxes(
    result: ScanResult,
    imageWidth: Float,
    imageHeight: Float,
    color: Color,
    strokeWidth: Float,
    alpha: Float = 1f,
) {
    for (paragraph in result.paragraphs) {
        paragraph.box?.toPixels(imageWidth, imageHeight)?.let { box ->
            val frame = box.frame.inflate(strokeWidth * 2)
            val radius = CornerRadius(min(strokeWidth * 4, min(frame.width, frame.height) / 4))
            rotate(box.rotationDegrees, box.center) {
                drawRoundRect(color, frame.topLeft, frame.size, radius, alpha = 0.10f * alpha)
                drawRoundRect(color, frame.topLeft, frame.size, radius, alpha = 0.85f * alpha, style = Stroke(strokeWidth))
            }
        }
        for (line in paragraph.lines) {
            val box = line.box?.toPixels(imageWidth, imageHeight) ?: continue
            rotate(box.rotationDegrees, box.center) {
                drawRect(color, box.frame.topLeft, box.frame.size, alpha = 0.18f * alpha)
            }
        }
    }
}

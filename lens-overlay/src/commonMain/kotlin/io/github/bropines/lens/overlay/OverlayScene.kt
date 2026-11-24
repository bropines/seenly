package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * A flat colour over a paragraph's hull, grown by [padding] pixels with round
 * corners. Nothing is painted inside [keepOut]: the padding reaches past the
 * text, and what lies there may be a paragraph that was not translated.
 */
internal class HullFillOp(val path: Path, val color: Color, val padding: Float, val keepOut: List<Rect>) : EraseOp

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

/** How many forced breaks one paragraph may get a hyphen for, before the rest is left as laid out. */
private const val MAX_HYPHENS = 64

/** Where [layout], a wrapped layout of [text], breaks a line inside a word, if it does. */
private fun splitWordAt(text: String, layout: TextLayoutResult): Int? =
    splitWordAt(text, (1 until layout.lineCount).map { layout.getLineStart(it) })

/** A font's size as a fraction of the thickness of a line set in it. */
private const val SOURCE_SIZE_RATIO = 0.9f

/**
 * Text re-set in a bubble may be this many times the source's line height
 * and no more (or the readable floor, if that is higher).
 */
private const val MAX_UPSCALE = 1.6f

/** Chromium's largest font size, in image pixels: the shaped fit's ceiling when the source has no lines. */
private const val MAX_SHAPED_FONT_PX = 150f

/** A bubble mask is axis-aligned, so it is only trusted for text within a degree of upright. */
private const val UPRIGHT_RADIANS = (PI / 180).toFloat()

/** How far past the paragraph its bubble is looked for, in multiples of its longer side. */
private const val WINDOW_PAD = 1.5f

/** The most cells a bubble is read at; a larger window is averaged down first. */
private const val MASK_PIXELS = 120_000f

/** A "bubble" over half the picture is the page, not a bubble. */
private const val MAX_COVER = 0.5f

private const val SQRT2 = 1.4142135f

/** The shape is used only when its text is at least this large against the rectangle's. */
private const val SHAPE_MIN_GAIN = 1.0f

// The geometry LayoutChoice.Auto reads a paragraph by.

/** Lines whose thickness differs by less than this factor are one text size. */
private const val SAME_SIZE = 1.25f

/** Fitted sizes further apart than this factor are a visible jump. */
private const val SIZE_JUMP = 1.4f

/** How far, in line heights, a line may start from the common edge and still share it. */
private const val EDGE_TOLERANCE = 0.6f

/** The range of centre-to-centre line spacing, in line heights, that body text keeps. */
private const val MIN_SPACING = 0.8f
private const val MAX_SPACING = 2.2f

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

    /**
     * What each paragraph occupies, translated or not: its box, or its lines'
     * boxes when it has none. Reflowed text must not grow over any of them. An
     * untranslated paragraph in particular is still the original, on the page.
     */
    private val paragraphObstacles: List<List<Rect>> = result.paragraphs.map { paragraph ->
        paragraph.box?.let { listOf(it.toPixels(width, height).bounds()) }
            ?: paragraph.lines.mapNotNull { it.box?.toPixels(width, height)?.bounds() }
    }

    /** Where the paragraphs Lens did not translate stand. They stay exactly as they are. */
    private val untranslated: List<Rect> =
        paragraphObstacles.filterIndexed { i, _ -> result.paragraphs[i].translation == null }.flatten()

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

        // Horizontal text cannot sensibly occupy a tall column, and a paragraph
        // whose lines carry no text of their own can only be set as a whole.
        var reflow = paragraphBox != null && !vertical &&
            (columns || style.layout == TranslationLayout.Reflow || !hasLineText)
        // The reflow layout says the page is bubbles; otherwise the paragraph has to.
        var bubble = style.layout == TranslationLayout.Reflow
        if (!reflow && !vertical && paragraphBox != null && style.reflowWhenCrowded && minFontPx > 0f) {
            // Index for index; a line with no text cannot be raised past anything.
            val rects = ArrayList<Rect>()
            val fitted = ArrayList<Float>()
            paragraph.lines.forEachIndexed { l, line ->
                val box = lineBoxes[l] ?: return@forEachIndexed
                rects += box.bounds()
                val text = line.translation?.trim().orEmpty()
                fitted += if (text.isEmpty()) minFontPx else fitLineSize(box, text, rtl) * style.textScale
            }
            if (rects.size >= 2) {
                // A column of short centred lines whose translation only reaches a
                // readable size by leaving its boxes is a bubble being read as a list.
                if (isBubbleColumn(rects, paragraphBox.bounds())) {
                    if (crowdsAtFloor(fitted, minFontPx)) {
                        reflow = true
                        bubble = true
                    }
                } else if (collidesAtFloor(rects, fitted, minFontPx)) {
                    // Whatever it is, lines written through each other are never right.
                    reflow = true
                }
            }
        }

        // Dense text: the paragraph as one block, not Lens's uneven split over lines.
        if (!reflow && !vertical && paragraphBox != null && hasLineText) {
            val boxes = lineBoxes.filterNotNull()
            val dense = when (style.reflowDenseParagraphs) {
                LayoutChoice.Off -> false
                LayoutChoice.On -> boxes.size >= DENSE_PARAGRAPH_LINES
                LayoutChoice.Auto -> looksLikeBodyText(boxes, rtl)
            }
            if (dense) reflow = true
        }

        val paragraphBackground: Int by lazy {
            paragraph.lines.firstNotNullOfOrNull { it.translationStyle?.backgroundColor }?.let(::opaque)
                ?: (paragraphBox ?: lineBoxes.firstNotNullOfOrNull { it })?.let(::sampleAround)
                ?: ARGB_WHITE
        }
        fun background(line: Int): Int =
            paragraph.lines[line].translationStyle?.backgroundColor?.let(::opaque) ?: paragraphBackground

        when (style.erase) {
            EraseMode.Fill -> eraseHull(
                lineBoxes.filterNotNull().ifEmpty { listOfNotNull(paragraphBox) },
                paragraphBackground,
                keepOut = untranslated,
            )
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
            val sources = lineBoxes.filterNotNull()
            val thickest = sources.maxOfOrNull { if (columns) it.width else it.height }
            val sizeCap = thickest?.let { it * SOURCE_SIZE_RATIO } ?: Float.MAX_VALUE
            val rectangle = reflowBlock(index, paragraphBox!!, text, rtl, alignment, ink, sizeCap)
            val shaped = if (style.bubbleShape) {
                shapedBlock(index, paragraphBox, sources, text, rtl, translation.targetLanguage, ink, bubble)
            } else {
                null
            }
            // The shape is there to give the text more room. Where it gives less
            // (a bubble cut by lettering Lens did not read, a tight outline), the
            // rectangle wins.
            if (shaped != null && shaped.size >= rectangle.size * SHAPE_MIN_GAIN) shaped.emit() else rectangle.emit()
        } else {
            // One size for the whole paragraph: the largest every line fits at.
            val shared = if (!vertical && style.uniformParagraphSize != LayoutChoice.Off) {
                val fits = paragraph.lines.indices.mapNotNull { l ->
                    val box = lineBoxes[l] ?: return@mapNotNull null
                    val text = paragraph.lines[l].translation?.trim().orEmpty()
                    if (text.isEmpty()) null else box to fitLineSize(box, text, rtl)
                }
                val wanted = when (style.uniformParagraphSize) {
                    LayoutChoice.On -> fits.size >= 2
                    // Source lines of one size whose translations came out at visibly different sizes.
                    else -> fits.size >= 2 && similarHeights(fits.map { it.first }) &&
                        fits.maxOf { it.second } > fits.minOf { it.second } * SIZE_JUMP
                }
                if (wanted) fits.minOf { it.second } else null
            } else {
                null
            }
            val growth = when (style.growFromLineStart) {
                LayoutChoice.Off -> 0f
                LayoutChoice.On -> if (rtl) -1f else 1f
                // Grow away from the side the paragraph is aligned to; centred text stays centred.
                LayoutChoice.Auto -> when (alignment.side(rtl)) {
                    Side.Left -> 1f
                    Side.Right -> -1f
                    Side.Center -> 0f
                }
            }
            paragraph.lines.forEachIndexed { l, line ->
                val box = lineBoxes[l] ?: return@forEachIndexed
                val text = line.translation?.trim().orEmpty()
                if (text.isEmpty()) return@forEachIndexed
                val ink = Ink(resolveTextColor(line.translationStyle?.textColor, background(l)), background(l))
                if (vertical) {
                    column(index, box, text, alignment, ink)
                } else {
                    // A line the floor raises past its box gets a background
                    // sized to it, unless a hull already covers the paragraph.
                    val grownBackground = if (style.erase == EraseMode.ServerPatch) background(l) else null
                    horizontalLine(index, box, text, rtl, alignment, ink, grownBackground, shared, growth)
                }
            }
        }
    }

    /** A text colour and the background it sits on, which is also its outline. */
    private class Ink(val text: Int, val background: Int)

    // ---- erasing -----------------------------------------------------------

    private fun eraseHull(boxes: List<OrientedBox>, color: Int, keepOut: List<Rect>) {
        val hull = convexHull(boxes.flatMap { it.corners() })
        if (hull.size < 3) return
        val thinnest = boxes.minOf { min(it.width, it.height) }
        val path = Path().apply {
            moveTo(hull[0].x, hull[0].y)
            for (i in 1 until hull.size) lineTo(hull[i].x, hull[i].y)
            close()
        }
        val padding = max(0f, thinnest * style.fillPadding)
        val reach = path.getBounds().inflate(padding)
        erases += HullFillOp(path, Color(color), padding, keepOut.filter { it.overlaps(reach) })
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
    private fun outlineWidth(size: Float, ink: Ink, rewrapped: Boolean): Float {
        if (style.outline <= 0f || contrastRatio(ink.text, ink.background) < 1.5) return 0f
        // Whole pixels, as the userscript strokes them: at least one on a line
        // in its own box, and twice as thick on re-wrapped text, which stands
        // on whatever the erasing left across a whole bubble.
        val visible = if (rewrapped) {
            (size * OUTLINE_RATIO * 2f * style.outline).roundToInt().toFloat()
        } else {
            max(1f, (size * OUTLINE_RATIO * style.outline).roundToInt().toFloat())
        }
        return visible * 2f
    }

    private fun add(
        paragraph: Int,
        box: OrientedBox,
        topLeft: Offset,
        size: Float,
        ink: Ink,
        quarterTurn: Boolean = false,
        rewrapped: Boolean = false,
        layout: (color: Color, outlineWidth: Float) -> TextLayoutResult,
    ) {
        val stroke = outlineWidth(size, ink, rewrapped)
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

    /**
     * calculateFontSizePixels(): the largest size at which [text] stays on one
     * line inside [box]. The height test uses the font's line box, not these
     * glyphs' ink, and "equal" already fails, which leaves slack.
     */
    /** Whether the lines are about one text size: their thickness varies by less than [SAME_SIZE]. */
    private fun similarHeights(boxes: List<OrientedBox>): Boolean =
        boxes.isNotEmpty() && boxes.maxOf { it.height } <= boxes.minOf { it.height } * SAME_SIZE

    /**
     * Whether a paragraph's lines read as body text rather than a sign or a
     * label: at least [DENSE_PARAGRAPH_LINES] of them, one size, all but at
     * most one (a first-line indent, a short last line's alignment) starting
     * at a common edge, and stacked at an ordinary line spacing.
     */
    private fun looksLikeBodyText(boxes: List<OrientedBox>, rtl: Boolean): Boolean {
        if (boxes.size < DENSE_PARAGRAPH_LINES || !similarHeights(boxes)) return false
        val h = boxes.map { it.height }.sorted()[boxes.size / 2]
        val angle = boxes[0].rotation
        val c = cos(angle)
        val s = sin(angle)
        // Each line's start along the reading direction, and its place across it.
        val starts = boxes.map { b ->
            val along = b.centerX * c + b.centerY * s
            if (rtl) -(along + b.width / 2) else along - b.width / 2
        }
        val edge = starts.sorted()[starts.size / 2]
        if (starts.count { abs(it - edge) > EDGE_TOLERANCE * h } > 1) return false
        val across = boxes.map { b -> -b.centerX * s + b.centerY * c }.sorted()
        return across.zipWithNext { a, b -> b - a }.all { it in (MIN_SPACING * h)..(MAX_SPACING * h) }
    }

    private fun fitLineSize(box: OrientedBox, text: String, rtl: Boolean): Float =
        fitFontSize(MIN_FONT_PX, max(MIN_FONT_PX, box.height)) { size ->
            val probe = measure(text, size, rtl)
            probe.size.width < box.width && probe.size.height < box.height
        }

    /**
     * @param grownBackground the colour to paint behind text the floor has
     *   raised past its box, or null to paint nothing there.
     */
    private fun horizontalLine(
        paragraph: Int,
        box: OrientedBox,
        text: String,
        rtl: Boolean,
        alignment: TextAlignment,
        ink: Ink,
        grownBackground: Int?,
        /** The paragraph's shared fitted size, or null to fit this line alone. */
        sharedFit: Float? = null,
        /** Which way a widened line grows along itself: 1 rightwards, -1 leftwards, 0 to both sides. */
        growth: Float = 0f,
    ) {
        val fitted = sharedFit ?: fitLineSize(box, text, rtl)
        var size = finalFontSize(fitted, style.textScale, minFontPx)
        var probe = measure(text, size, rtl)
        // Nothing may be drawn where the image will only clip it: a floor that
        // makes the line wider than the picture gives way to the picture (one
        // proportional step; text width is linear in the size).
        val room = width * 0.98f
        if (size > fitted && probe.size.width > room) {
            size = max(fitted, floor(size * room / probe.size.width / FONT_STEP_PX) * FONT_STEP_PX)
            probe = measure(text, size, rtl)
        }
        val textWidth = probe.size.width.toFloat()
        // Raised past what the box holds: the text needs room the box does not
        // have. Sized by the measured text, never by size / fitted, which has
        // nothing bounding it and paints bars across the page.
        val enlarged = size > fitted
        val drawW = if (enlarged) min(max(box.width, textWidth + size * 0.4f), width) else box.width
        val drawH = if (enlarged) min(max(box.height, size * 1.35f), height) else box.height
        // Grown from one end: the centre moves along the line by half of what was added.
        val anchor = if (growth != 0f && drawW > box.width) {
            val along = (drawW - box.width) / 2 * growth
            box.copy(centerX = box.centerX + along * cos(box.rotation), centerY = box.centerY + along * sin(box.rotation))
        } else {
            box
        }
        val shift = nudgeInside(anchor, drawW, drawH, rtl)
        val area = anchor.copy(centerX = anchor.centerX + shift.x, centerY = anchor.centerY + shift.y, width = drawW, height = drawH)
        if (enlarged && grownBackground != null) erases += RectFillOp(area, Color(grownBackground))

        val frame = area.frame
        val x = when (alignment.side(rtl)) {
            Side.Left -> frame.left
            Side.Right -> frame.right - textWidth
            Side.Center -> area.centerX - textWidth / 2
        }
        val y = area.centerY - probe.size.height / 2f
        add(paragraph, area, Offset(x, y), size, ink) { color, outline ->
            measure(text, size, rtl, color, outlineWidth = outline)
        }
    }

    /**
     * How far to move a [drawW] x [drawH] box, turned like [box] and centred
     * on it, so that it lies inside the image, in image pixels. A line the
     * floor has widened near an edge would otherwise be clipped: a whole
     * sentence a few pixels off its place beats half a sentence in it. A box
     * too big to fit at all keeps the start of the line, the half read first.
     */
    private fun nudgeInside(box: OrientedBox, drawW: Float, drawH: Float, rtl: Boolean): Offset {
        val c = abs(cos(box.rotation))
        val s = abs(sin(box.rotation))
        val halfW = (drawW * c + drawH * s) / 2
        val halfH = (drawW * s + drawH * c) / 2
        fun shift(centre: Float, half: Float, limit: Float, fromEnd: Boolean): Float = when {
            half * 2 >= limit -> if (fromEnd) limit - half - centre else half - centre
            centre - half < 0f -> half - centre
            centre + half > limit -> limit - half - centre
            else -> 0f
        }
        return Offset(shift(box.centerX, halfW, width, rtl), shift(box.centerY, halfH, height, false))
    }

    // ---- Reflow layout: the paragraph wrapped into its box ------------------

    /** A laid-out block not yet added to the scene: its font size, and how to add it. */
    private class Plan(val size: Float, val emit: () -> Unit)

    /**
     * Lays the whole translation out as one wrapped block in a rectangle.
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
    ): Plan {
        val align = when (alignment) {
            TextAlignment.Start -> TextAlign.Start
            TextAlignment.End -> TextAlign.End
            TextAlignment.Center -> TextAlign.Center
        }
        fun fit(area: OrientedBox): Float =
            fitFontSize(MIN_FONT_PX, max(MIN_FONT_PX, min(area.height, sizeCap))) { size ->
                val probe = measure(text, size, rtl, wrapWidth = area.width, align = align)
                // The widest unbreakable word has to fit as well: text is shrunk
                // before a word is ever split across lines. The layout itself is
                // asked too, since it is what will be drawn.
                probe.size.height <= area.height &&
                    probe.multiParagraph.minIntrinsicWidth <= area.width + 0.5f &&
                    splitWordAt(text, probe) == null
            }

        // Grow only as far as the text needs: a paragraph that already reads
        // at the source's size stays exactly where the source was.
        val growth = style.reflowGrowth
        // Even ungrown, the area gives way to a neighbour that overlaps the box's edge.
        var area = reflowArea(paragraph, box, 1f)
        var fitted = fit(area)
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
        var size = finalFontSize(fitted, style.textScale, minFontPx)
        fun splitsWord(at: Float): Boolean =
            splitWordAt(text, measure(text, at, rtl, wrapWidth = area.width, align = align)) != null
        // The readable-size floor and the text scale can ask for more than the
        // box holds. Overflowing its height is their business; tearing a word
        // in two is not, so they give way until the longest word fits a line.
        if (size > MIN_FONT_PX && splitsWord(size)) {
            size = fitFontSize(MIN_FONT_PX, size) { !splitsWord(it) }
        }
        // A paragraph taller than its room is one written across the next
        // bubble, so the floor gives way here too, down to what the room holds:
        // text that shrinks is readable, text over its neighbour is not.
        if (size > fitted) {
            fun overflows(at: Float): Boolean =
                measure(text, at, rtl, wrapWidth = area.width, align = align).size.height > area.height + 0.5f
            if (overflows(size)) size = fitFontSize(fitted, size) { !overflows(it) && !splitsWord(it) }
        }
        // A word wider than the box even at the smallest size has to be broken
        // after all. It is then broken properly, with a hyphen.
        var shown = text
        var guard = 0
        while (guard++ < MAX_HYPHENS) {
            val split = splitWordAt(shown, measure(shown, size, rtl, wrapWidth = area.width, align = align)) ?: break
            shown = hyphenateAt(shown, split)
        }
        var wrap = area.width
        var probe = measure(shown, size, rtl, wrapWidth = wrap, align = align)
        // Balanced lines: the same line count at the narrowest width that does
        // not add a line, so the lines come out of similar length and no word
        // is left alone at the end. Not once hyphens were needed: those are
        // placed for the full width.
        if (shown == text && probe.lineCount >= 2) {
            val lines = probe.lineCount
            var lo = probe.multiParagraph.minIntrinsicWidth
            var hi = wrap
            repeat(10) {
                if (hi - lo < 1f) return@repeat
                val mid = (lo + hi) / 2
                val tried = measure(shown, size, rtl, wrapWidth = mid, align = align)
                if (tried.lineCount == lines && splitWordAt(shown, tried) == null) {
                    hi = mid
                    wrap = mid
                    probe = tried
                } else {
                    lo = mid
                }
            }
        }
        val x = area.centerX - probe.size.width / 2f
        var y = area.centerY - probe.size.height / 2f
        if (area.upright && probe.size.height <= height) y = y.coerceIn(0f, height - probe.size.height)
        return Plan(size) {
            add(paragraph, area, Offset(x, y), size, ink, rewrapped = true) { color, outline ->
                measure(shown, size, rtl, color, wrapWidth = wrap, align = align, outlineWidth = outline)
            }
        }
    }

    // ---- Shaped layout: the paragraph wrapped into its bubble ----------------

    /**
     * Sets the whole translation inside the bubble around [box], each line as
     * wide as the bubble is at its height, or returns false when there is no
     * shape to use or the text does not fit it at any size, and the rectangle
     * is used instead.
     *
     * The shape is the bubble's outline read from the picture; failing that,
     * for a paragraph known to be a bubble ([bubble]), the ellipse inscribed in
     * its room grown by the square root of two: the smallest ellipse that
     * contains a box is that box grown by exactly that.
     */
    private fun shapedBlock(
        paragraph: Int,
        box: OrientedBox,
        lines: List<OrientedBox>,
        text: String,
        rtl: Boolean,
        targetLanguage: String?,
        ink: Ink,
        bubble: Boolean,
    ): Plan? {
        // A mask is read along the image's axes, so only upright text gets one.
        if (abs(box.rotation) > UPRIGHT_RADIANS) return null
        val others = paragraphObstacles.filterIndexed { i, _ -> i != paragraph }.flatten()
        val own = box.bounds()
        val outline = findBubble(own, lines.map { it.bounds() }, others)
            ?: if (bubble) {
                val room = roomFor(own, others, max(style.reflowGrowth, SQRT2), width, height)
                ellipseShape(room.center.x, room.center.y, room.width / 2, room.height / 2)
            } else {
                null
            }
            ?: return null
        // Centred on the source paragraph's own axis, symmetric and smooth.
        val axis = box.centerX
        val shape = symmetricShape(outline, axis)

        // A line box's short side is the height of the lettering, whichever way
        // the source ran. A bubble is often far larger than what is said in it,
        // and two words blown up to fill one read as shouting.
        val sourceSize = lines.map { min(it.width, it.height) }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val ceiling = if (sourceSize > 0f) max(sourceSize * MAX_UPSCALE, minFontPx) else MAX_SHAPED_FONT_PX
        val perCharacter = wrapsPerCharacter(targetLanguage)
        val advance = { s: String, size: Float -> measure(s, size, rtl).multiParagraph.maxIntrinsicWidth }
        var fit = fitShaped(advance, text, shape, REFLOW_LINE_SPACING, perCharacter, ceiling) ?: return null
        // Below the floor the floor gives way rather than the shape. A text
        // scale under one asks for air: the largest fit no bigger than that.
        if (style.textScale < 1f) {
            fitShaped(advance, text, shape, REFLOW_LINE_SPACING, perCharacter, fit.size * style.textScale)?.let { fit = it }
        }

        val size = fit.size
        val lines = fit.lines
        return Plan(size) { emitShaped(paragraph, lines, size, axis, rtl, ink) }
    }

    private fun emitShaped(paragraph: Int, lines: List<ShapedLine>, size: Float, axis: Float, rtl: Boolean, ink: Ink) {
        val upright = OrientedBox(0f, 0f, 0f, 0f, 0f)
        // Every line on the one axis; the bands are symmetric about it.
        for (line in lines) {
            val probe = measure(line.text, size, rtl)
            val w = probe.multiParagraph.maxIntrinsicWidth
            // A bubble is centred whatever the source's alignment says; only
            // someone who asked for a side gets one.
            val x = when (style.alignment?.side(rtl)) {
                Side.Left -> line.left
                Side.Right -> line.right - w
                else -> (axis - w / 2).coerceIn(line.left, max(line.left, line.right - w))
            }
            // The em box sits in the middle of its pitch; the layout's line box
            // is centred on it.
            val y = line.y + size / 2 - probe.size.height / 2f
            add(paragraph, upright, Offset(x, y), size, ink, rewrapped = true) { color, outline ->
                measure(line.text, size, rtl, color, outlineWidth = outline)
            }
        }
    }

    /**
     * The bubble a paragraph stands in, from the picture's own pixels, or null.
     *
     * The window is [own] padded on every side, cut back by the picture and by
     * a neighbour sharing the band (two paragraphs in one bubble each get their
     * half). An edge the padding put there is "open", and a fill that reaches
     * one was not inside anything. Large windows are read averaged down to at
     * most [MASK_PIXELS] cells, which keeps the cost to milliseconds.
     */
    private fun findBubble(own: Rect, lines: List<Rect>, others: List<Rect>): TextShape? {
        val pad = max(own.width, own.height) * WINDOW_PAD
        if (!(pad > 0f)) return null
        val room = bandRoom(own, others, 1e6f, width, height)
        val open = Sides(
            left = own.left - pad > room.left,
            top = own.top - pad > room.top,
            right = own.right + pad < room.right,
            bottom = own.bottom + pad < room.bottom,
        )
        val sx = floor(max(room.left, own.left - pad)).toInt().coerceIn(0, image.width)
        val sy = floor(max(room.top, own.top - pad)).toInt().coerceIn(0, image.height)
        val sw = ceil(min(room.right, own.right + pad)).toInt().coerceIn(0, image.width) - sx
        val sh = ceil(min(room.bottom, own.bottom + pad)).toInt().coerceIn(0, image.height) - sy
        if (sw < 2 || sh < 2) return null
        val shrink = min(1f, sqrt(MASK_PIXELS / (sw.toFloat() * sh)))
        val gw = max(1, (sw * shrink).roundToInt())
        val gh = max(1, (sh * shrink).roundToInt())
        val grid = readGrid(sx, sy, sw, sh, gw, gh) ?: return null
        val scaleX = sw.toFloat() / gw
        val scaleY = sh.toFloat() / gh
        fun toGrid(rect: Rect, grow: Float = 0f) = Rect(
            (rect.left - grow - sx) / scaleX,
            (rect.top - grow - sy) / scaleY,
            (rect.right + grow - sx) / scaleX,
            (rect.bottom + grow - sy) / scaleY,
        )
        val mask = bubbleMask(
            grid, gw, gh,
            lines.map { toGrid(it) },
            // The same daylight the room keeps between two paragraphs.
            others.map { toGrid(it, BAND_GAP) },
            open,
            MAX_COVER * width * height / (scaleX * scaleY),
        ) ?: return null
        return maskShape(mask, sx.toFloat(), sy.toFloat(), scaleX, scaleY)
    }

    /**
     * The image's pixels in `sx, sy, sw x sh`, averaged down to `gw x gh`
     * opaque cells (anything translucent over white: transparent would read as
     * black, and black is the one colour an outline is). Read in bands of rows,
     * so a big picture never needs a full copy. Null if it cannot be read.
     */
    private fun readGrid(sx: Int, sy: Int, sw: Int, sh: Int, gw: Int, gh: Int): IntArray? = try {
        val cells = gw * gh
        val red = IntArray(cells)
        val green = IntArray(cells)
        val blue = IntArray(cells)
        val count = IntArray(cells)
        val band = max(1, min(sh, 65_536 / sw))
        val buffer = IntArray(sw * band)
        val column = IntArray(sw) { it * gw / sw }
        var y0 = 0
        while (y0 < sh) {
            val rows = min(band, sh - y0)
            readable.readPixels(buffer, sx, sy + y0, sw, rows)
            for (ry in 0 until rows) {
                val rowBase = ((y0 + ry) * gh / sh) * gw
                for (x in 0 until sw) {
                    val p = buffer[ry * sw + x]
                    val a = p ushr 24
                    val back = 255 - a
                    val cell = rowBase + column[x]
                    red[cell] += (((p shr 16) and 0xFF) * a + 255 * back) / 255
                    green[cell] += (((p shr 8) and 0xFF) * a + 255 * back) / 255
                    blue[cell] += ((p and 0xFF) * a + 255 * back) / 255
                    count[cell]++
                }
            }
            y0 += rows
        }
        IntArray(cells) { i ->
            val n = max(1, count[i])
            ARGB_BLACK or ((red[i] / n) shl 16) or ((green[i] / n) shl 8) or (blue[i] / n)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The area a reflowed paragraph may use: its box, grown by [growth] (up to
     * [OverlayStyle.reflowGrowth]) where neighbours and the image leave room.
     * Height grows less than width (at most a fifth): bubbles are stacked more
     * tightly than they are packed side by side. A visibly rotated box is left
     * alone, since room is measured along the image's axes.
     */
    private fun reflowArea(paragraph: Int, box: OrientedBox, growth: Float): OrientedBox {
        if (abs(box.rotation) > 0.05f) return box
        val own = box.frame
        val others = paragraphObstacles.filterIndexed { i, _ -> i != paragraph }.flatten()
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

            is HullFillOp -> drawIntoCanvas { canvas ->
                canvas.save()
                for (rect in op.keepOut) canvas.clipRect(rect, ClipOp.Difference)
                drawPath(op.path, op.color)
                if (op.padding > 0f) {
                    // A round-jointed stroke grows the shape by the padding in
                    // every direction and rounds its corners in one step.
                    drawPath(op.path, op.color, style = Stroke(op.padding * 2, join = StrokeJoin.Round))
                }
                canvas.restore()
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

package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.bropines.lens.protocol.LineStyle
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

// Where every piece of translated text goes, and at what size.
//
// A page is laid out as a typesetter would set it, not paragraph by paragraph:
//
// 1. Columns Lens returned as separate paragraphs but that stand in one bubble
//    are put back together.
// 2. Each paragraph is read for what it is: a bubble (wrapped into the shape
//    of its balloon), running text (wrapped into its own box on the source's
//    lines), lines that stand for themselves (labels, lists, headings) or
//    vertical columns that stay vertical.
// 3. Each is fitted on its own, and then text that was one size in the source
//    is brought to one size on the page.
//
// Nothing is ever drawn over another paragraph, and text leaves its own box
// only into room the picture shows to be empty.

/** Chromium's `getOutlineStyleForLine()`: an outline of this fraction of the font size. */
private const val OUTLINE_RATIO = 0.02f

/** An upright glyph's cell in a vertical column, in font sizes. */
private const val VERTICAL_CELL = 1.05f

/** How far the size read off a column's length may exceed the size read off its thickness. */
private const val COLUMN_PITCH_TRUST = 1.7f

/** How many forced breaks one paragraph may get a hyphen for, before the rest is left as laid out. */
private const val MAX_HYPHENS = 64

/** A font's size as a fraction of the thickness of a line set in it. */
private const val SOURCE_SIZE_RATIO = 0.9f

/**
 * Text re-set in a bubble may be this many times the source's line thickness
 * and no more (or the readable floor, if that is higher). A bubble is often
 * far larger than what is said in it, and a few words blown up to fill one
 * read as shouting, next to a neighbour that is full.
 */
private const val MAX_UPSCALE = 1.25f

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

/** A guessed shape (no outline was found) is used only when its text is at least this large against the rectangle's. */
private const val SHAPE_MIN_GAIN = 1.0f

// The geometry LayoutChoice.Auto reads a paragraph by. Measured on the corpus
// (see QualityContract's geometry report): lines of one size, with their size
// taken from how wide their words run, never differ by more than a tenth;
// running text keeps its lines within a fifth of a font size of their common
// edge, while the lines of a centred bubble are half a size off it or more;
// and running text is leaded between 1.2 and 1.5.

/** Lines whose type differs by less than this factor are one text size. */
private const val SAME_SIZE = 1.25f

/** Fitted sizes further apart than this factor are a visible jump (the line-by-line layout that grows from the centre). */
private const val SIZE_JUMP = 1.4f

/** How far, in font sizes, a line may start from the common edge and still share it. */
private const val EDGE_TOLERANCE = 0.35f

/** The range of centre-to-centre line spacing, in font sizes, that running text keeps. */
private const val MIN_SPACING = 0.9f
private const val MAX_SPACING = 1.9f

/** Running text is at least this many font sizes wide; two lines alone need [PROSE_PAIR_WIDTH]. */
private const val PROSE_MIN_WIDTH = 6f
private const val PROSE_PAIR_WIDTH = 10f

/** How much room, in font sizes, a line may have left over and still be full: the next word did not fit. */
private const val WRAP_SLACK = 0.5f

/** Vertical columns this many thicknesses long, three or more of them, are a page of prose, not a bubble. */
private const val PROSE_COLUMN_LENGTH = 10f
private const val PROSE_COLUMNS = 3

/** The tightest pitch running text is set at, in font sizes: below it the lines touch. */
private const val LINE_TIGHT = 1.15f

/** The tightest pitch of the source itself that is followed as it is; a source set tighter is set smaller. */
private const val LINE_SOURCE = 1.02f

/** The size widths are measured at once; they scale with the size (see [SceneBuilder.textStyle]). */
private const val REFERENCE_SIZE = 40f

/**
 * How far above and below the middle of its line box a line's ink reaches, in
 * font sizes: an ascender and a descender. Less above than below, since the
 * line box keeps more room over the letters than under them.
 */
private const val INK_UP = 0.46f
private const val INK_DOWN = 0.58f

/**
 * How far around what is erased for a line the picture is not asked, as a
 * share of the line's height: the soft edge of the source's own letters, which
 * a box drawn around their ink does not hold and which is no reason to keep
 * the new letters small.
 */
private const val HALO = 0.18f

/** Clear space kept between text that has left its box and the next paragraph, in pixels. */
private const val CLEAR_GAP = 2f

/** The picture is asked about room in slices this long, so a tilted line's room is not its bounding box. */
private const val CLEAR_SLICE = 48f

/** A line this short says too little about its type's size by its width. */
private const val EM_MIN_LETTERS = 4

/** How far the size read off a line's width may leave the size read off its height. */
private const val EM_BELOW = 0.8f
private const val EM_ABOVE = 1.25f

/** How many lines the page's font is compared to the layout font by. */
private const val BIAS_SAMPLES = 48

/**
 * Latin or Cyrillic set to look the size of the ideographs it replaces: a
 * square glyph fills its body, a lowercase letter about half of it.
 */
private const val CJK_MATCH = 1.1f

// Text that was one size in the source is one size on the page.

/** Paragraphs whose type is within this factor, each of the next, are one class... */
private const val CLASS_STEP = 1.12f

/** ...as long as the whole class spans no more than this. */
private const val CLASS_SPAN = 1.3f

/** A class is set no smaller than this share of what its middle member could have had. */
private const val CLASS_GIVE = 0.9f

/**
 * Text in colours further apart than this, in any channel, is not one class
 * whatever its size. Lens reports a line's colour mixed with its background,
 * so two greys of one voice differ by a couple of dozen and an orange from a
 * grey by little more.
 */
private const val CLASS_COLOUR = 36

/**
 * Running text keeps to the source's own lines when that costs it no more
 * than this share of the size it could have with its lines pushed together...
 */
private const val GRID_KEEP = 0.86f

/** ...and leaves it no smaller than this share of the size the source was set in. */
private const val GRID_FLOOR = 0.78f

/** How many lines' worth of empty room under a paragraph of running text is looked at for it to run on into. */
private const val PROSE_EXTRA_LINES = 2.5f

/** How much of that room stays empty under it, in lines: paragraphs are told apart by the gap between them. */
private const val PROSE_GAP_LINES = 0.6f

private const val NO_FAMILY = -1
private const val FAMILY_BUBBLE = 0
private const val FAMILY_TEXT = 1

// Columns Lens split that stand in one bubble.

/** Columns of one bubble are lettered at one size, within this factor. */
private const val MERGE_SIZE = 1.3f

/** They stand at most this many column widths apart... */
private const val MERGE_GAP = 1.0f

/** ...share at least this much of the shorter one's height... */
private const val MERGE_OVERLAP = 0.3f

/** ...and at most this share of the rows between them has anything drawn on it: an outline crosses every row. */
private const val MERGE_BUSY = 0.35f

private enum class Side { Left, Center, Right }

private fun TextAlignment.side(rtl: Boolean): Side = when (this) {
    TextAlignment.Start -> if (rtl) Side.Right else Side.Left
    TextAlignment.End -> if (rtl) Side.Left else Side.Right
    TextAlignment.Center -> Side.Center
}

private fun opaque(argb: Int): Int = argb or ARGB_BLACK

private fun median(values: List<Float>): Float = values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

private fun snap(size: Float): Float = floor(size / FONT_STEP_PX) * FONT_STEP_PX

/** Where [layout], a wrapped layout of [text], breaks a line inside a word, if it does. */
private fun splitWordAt(text: String, layout: TextLayoutResult): Int? =
    splitWordAt(text, (1 until layout.lineCount).map { layout.getLineStart(it) })

/** One source line of a block: where it stood and what replaces it. */
private class BlockLine(
    val paragraph: Int,
    val line: Int,
    val box: OrientedBox?,
    val source: String,
    /** This line's share of the translation, trimmed; empty when it has none. */
    val text: String,
    val style: LineStyle?,
)

/**
 * What is laid out as one piece: a paragraph, or several that Lens split and
 * that belong together ([members]).
 */
private class Block(
    /** The paragraph its text is attributed to. */
    val index: Int,
    val members: List<Int>,
    val lines: List<BlockLine>,
    val box: OrientedBox?,
    /** The whole translation. */
    val text: String,
    /** Whether the source ran in vertical columns. */
    val columns: Boolean,
    /** Whether the translation is drawn in vertical columns too. */
    val vertical: Boolean,
    val rtl: Boolean,
    val alignment: TextAlignment,
    val target: String?,
    val justified: Boolean,
) {
    val boxes: List<OrientedBox> = lines.mapNotNull { it.box }
    val hasLineText: Boolean = lines.any { it.box != null && it.text.isNotEmpty() }

    /** The thickness of the middle line: across a column, down a row. */
    val lettering: Float = median(boxes.map { if (columns) it.width else it.height })

    /**
     * The size of the type a block of columns was lettered in: of its middle
     * column, see [columnEm].
     */
    val columnType: Float = median(lines.filter { it.box != null }.map { columnEm(it.box!!, it.source) })
}

/** How many cells of a vertical column [text] takes: a glyph each, three dots of a leader in one. */
private fun columnCells(text: String): Int {
    var cells = 0
    var dots = 0
    for (char in text) {
        when {
            char.isWhitespace() -> Unit
            char == '･' || char == '・' || char == '.' || char == '·' -> dots++
            else -> cells++
        }
    }
    return cells + (dots + 2) / 3
}

/**
 * The size of the type in a vertical column. A column is as thick as its
 * widest glyph, which says little when it holds a small kana and an
 * exclamation mark; but it is as long as its glyphs are many, and each takes
 * one cell. The length is believed up to [COLUMN_PITCH_TRUST] times what the
 * thickness says: Lens can miss a glyph, and then the cells come out too long.
 */
private fun columnEm(box: OrientedBox, source: String): Float {
    val byWidth = box.width / INK_FULL
    val cells = columnCells(source)
    if (cells < 2) return byWidth
    val byPitch = box.height / cells / VERTICAL_CELL
    return max(byWidth, min(byPitch, byWidth * COLUMN_PITCH_TRUST))
}

/** A laid-out block not yet added to the scene: its font size, and how to add it. */
private class Plan(val size: Float, val emit: () -> Unit)

/**
 * What a draft gets on its own: its [size], and the size it would have had
 * without the readable floor raising it ([plain]).
 */
private class Fitted(val size: Float, val plain: Float = size)

/**
 * A block waiting to hear what size its class is set at. One block is one
 * draft, except lines of different sizes that Lens put in one paragraph, which
 * are a draft each.
 *
 * It is fitted when first asked, which is after every draft of the page
 * exists: lines that face each other across a gap share it.
 */
private class Draft(
    val block: Block,
    /** Bubbles are sized with bubbles, text with text; [NO_FAMILY] is sized alone. */
    val family: Int,
    /** The size of the source's type: what picks the class. */
    val em: Float,
    /** The colour its text is drawn in: type of one size in two colours is two classes. */
    val colour: Int,
    /** What the block was taken for, for a trace. */
    val kind: String,
    fit: () -> Fitted,
    /** Lays it out no larger than [cap] and adds it to the scene. */
    val emit: (cap: Float) -> Unit,
) {
    private val fitted: Fitted by lazy(LazyThreadSafetyMode.NONE, fit)

    /** The size it gets on its own. */
    val natural: Float get() = fitted.size

    /** The same without the readable floor's help. */
    val plain: Float get() = fitted.plain
}

/** A text colour and the background it sits on, which is also its outline. */
private class Ink(val text: Int, val background: Int)

internal class SceneBuilder(
    private val state: OverlayState,
    private val result: ScanResult,
    private val style: OverlayStyle,
    private val image: ImageBitmap,
    private val minFontPx: Float,
    private val trace: ((String) -> Unit)? = null,
) {
    private val width = image.width.toFloat()
    private val height = image.height.toFloat()

    // Density 1 makes one sp one image pixel. No cache of its own: the probes
    // asked for twice are kept below, with the locale they were measured in.
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

    /** The locale of the block being laid out: it picks the regional face for CJK text. */
    private var locale: LocaleList? = null
    private var localeTag: String? = null

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

    /** A line of the page (or a paragraph that has none), as something text set elsewhere keeps clear of. */
    private class Occupied(val paragraph: Int, val line: Int, val box: OrientedBox, val bounds: Rect)

    private val occupied: List<Occupied> = buildList {
        result.paragraphs.forEachIndexed { p, paragraph ->
            val lines = paragraph.lines.mapIndexedNotNull { l, line ->
                line.box?.toPixels(width, height)?.let { Occupied(p, l, it, it.bounds()) }
            }
            if (lines.isNotEmpty()) {
                addAll(lines)
            } else {
                paragraph.box?.toPixels(width, height)?.let { add(Occupied(p, -1, it, it.bounds())) }
            }
        }
    }

    /** Only made when a colour has to be sampled; on Android it may copy the image. */
    private val readable: ImageBitmap by lazy { image.forSoftwareCanvas() }

    /** Where the picture has something drawn; null when it cannot be read. Made when first asked. */
    private val detail: DetailMap? by lazy { detailOf(readable) }

    fun build(): OverlayScene {
        val blocks = mergeColumns(result.paragraphs.mapIndexedNotNull(::blockOf))
        val together = style.uniformParagraphSize != LayoutChoice.Off
        // Chromium's own layout knows nothing of the rest of the page: each
        // paragraph is drawn as soon as it is fitted, in the order it came.
        val oneByOne = !together && style.growFromLineStart == LayoutChoice.Off
        val drafts = ArrayList<Draft>()
        for (block in blocks) {
            val made = within(block) { draft(block) }
            if (oneByOne) within(block) { made.forEach { it.emit(Float.MAX_VALUE) } } else drafts += made
        }
        if (!oneByOne) {
            for (draft in drafts) within(draft.block) { draft.natural }
            val caps = if (together) sharedSizes(drafts) else FloatArray(drafts.size) { Float.MAX_VALUE }
            drafts.forEachIndexed { i, draft ->
                trace?.invoke(
                    "p${draft.block.index} ${draft.kind} em=${draft.em} natural=${draft.natural} plain=${draft.plain} " +
                        "cap=${if (caps[i] >= Float.MAX_VALUE / 2) "-" else caps[i].toString()} '${draft.block.text.take(40)}'",
                )
                within(draft.block) { draft.emit(caps[i]) }
            }
        }
        return OverlayScene(state, image.width, image.height, erases, texts)
    }

    private inline fun <T> within(block: Block, body: () -> T): T {
        localeTag = block.target
        locale = block.target?.let { tag ->
            try {
                LocaleList(tag)
            } catch (e: Exception) {
                null
            }
        }
        return body()
    }

    // ---- blocks ------------------------------------------------------------

    private fun blockOf(index: Int, paragraph: Paragraph): Block? {
        val translation = paragraph.translation ?: return null
        val usable: (OrientedBox) -> Boolean = { it.width >= 1f && it.height >= 1f }
        val box = paragraph.box?.toPixels(width, height)?.takeIf(usable)
        // The source's direction counts too: its line boxes are tall columns
        // whatever direction the translation claims for itself.
        val columns = translation.writingDirection == WritingDirection.TopToBottom ||
            paragraph.writingDirection == WritingDirection.TopToBottom
        val lines = paragraph.lines.mapIndexed { l, line ->
            BlockLine(
                paragraph = index,
                line = l,
                box = line.box?.toPixels(width, height)?.takeIf(usable),
                source = line.text,
                text = line.translation?.trim().orEmpty(),
                style = line.translationStyle,
            )
        }
        val block = Block(
            index = index,
            members = listOf(index),
            lines = lines,
            box = box,
            text = translation.text.trim(),
            columns = columns,
            vertical = columns && keepsVertical(style.verticalText, translation.targetLanguage),
            rtl = translation.writingDirection == WritingDirection.RightToLeft || isRtlLanguage(translation.targetLanguage),
            alignment = style.alignment ?: translation.alignment,
            target = translation.targetLanguage?.takeIf { it.isNotBlank() },
            justified = translation.justified,
        )
        return if (!block.hasLineText && box == null) null else block
    }

    /**
     * Puts back together the columns of one bubble that Lens returned as
     * paragraphs of their own, which it does with heavy lettering and with
     * columns of uneven length. Each was translated alone, and set alone each
     * would get a sliver of the bubble: together they read, right to left, as
     * the bubble's text.
     */
    private fun mergeColumns(blocks: List<Block>): List<Block> {
        val candidates = blocks.indices.filter { i ->
            val block = blocks[i]
            block.columns && !block.vertical && block.box != null && abs(block.box.rotation) <= UPRIGHT_RADIANS * 3
        }
        if (candidates.size < 2) return blocks
        val group = IntArray(blocks.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (group[r] != r) r = group[r]
            return r
        }
        for (a in candidates.indices) {
            for (b in a + 1 until candidates.size) {
                val i = candidates[a]
                val j = candidates[b]
                if (root(i) != root(j) && sameBubble(blocks[i], blocks[j])) group[root(j)] = root(i)
            }
        }
        val merged = ArrayList<Block>()
        val done = HashSet<Int>()
        for (i in blocks.indices) {
            val r = root(i)
            if (!done.add(r)) continue
            // Read right to left, as columns are.
            val members = blocks.indices.filter { root(it) == r }.map { blocks[it] }.sortedByDescending { it.box?.centerX ?: 0f }
            if (members.size == 1) {
                merged += members[0]
                continue
            }
            val bounds = members.map { it.box!!.bounds() }
            val left = bounds.minOf { it.left }
            val top = bounds.minOf { it.top }
            val right = bounds.maxOf { it.right }
            val bottom = bounds.maxOf { it.bottom }
            val first = members[0]
            merged += Block(
                index = members.minOf { it.index },
                members = members.flatMap { it.members },
                lines = members.flatMap { it.lines },
                box = OrientedBox((left + right) / 2, (top + bottom) / 2, right - left, bottom - top, 0f),
                text = members.joinToString(" ") { it.text }.trim(),
                columns = true,
                vertical = false,
                rtl = first.rtl,
                // Several columns of one bubble: whatever each said of itself, together they are centred.
                alignment = style.alignment ?: TextAlignment.Center,
                target = first.target,
                justified = false,
            )
        }
        return merged
    }

    /** Whether two paragraphs of vertical columns stand side by side in one bubble. */
    private fun sameBubble(a: Block, b: Block): Boolean {
        val ra = a.box!!.bounds()
        val rb = b.box!!.bounds()
        if (!(a.columnType > 0f) || !(b.columnType > 0f)) return false
        if (max(a.columnType, b.columnType) > min(a.columnType, b.columnType) * MERGE_SIZE) return false
        val size = (a.columnType + b.columnType) / 2
        val gap = max(ra.left, rb.left) - min(ra.right, rb.right)
        if (gap > size * MERGE_GAP || gap < -size / 2) return false
        val top = max(ra.top, rb.top)
        val bottom = min(ra.bottom, rb.bottom)
        if (bottom - top < MERGE_OVERLAP * min(ra.height, rb.height)) return false
        if (gap < 2f) return true
        // Between two bubbles there are two outlines, on every row. Between
        // two columns of one there is the bubble, with a comma at most.
        return try {
            val x = floor(min(ra.right, rb.right)).toInt().coerceIn(0, image.width - 1)
            val y = floor(top).toInt().coerceIn(0, image.height - 1)
            val w = (ceil(max(ra.left, rb.left)).toInt() - x).coerceIn(1, image.width - x)
            val h = (ceil(bottom).toInt() - y).coerceIn(1, image.height - y)
            val pixels = IntArray(w * h)
            readable.readPixels(pixels, x, y, w, h)
            val ground = medianColor(pixels) ?: return false
            var busy = 0
            for (row in 0 until h) {
                for (column in 0 until w) {
                    val p = pixels[row * w + column]
                    val off = max(
                        abs(((p shr 16) and 0xFF) - ((ground shr 16) and 0xFF)),
                        max(abs(((p shr 8) and 0xFF) - ((ground shr 8) and 0xFF)), abs((p and 0xFF) - (ground and 0xFF))),
                    )
                    if (off > BG_TOLERANCE) {
                        busy++
                        break
                    }
                }
            }
            busy <= h * MERGE_BUSY
        } catch (e: Exception) {
            // Unreadable: only columns that all but touch are taken for one bubble.
            gap <= size * MERGE_GAP / 2
        }
    }

    /** Where everything but [block] stands. */
    private fun othersOf(block: Block): List<Rect> =
        paragraphObstacles.filterIndexed { i, _ -> i !in block.members }.flatten()

    // ---- reading a block ---------------------------------------------------

    private val ems = HashMap<BlockLine, Float>()

    /**
     * How the page's own type runs against the layout font: the size a line's
     * height says over the size its width says, taken over the page. A width
     * tells sizes apart far better than a height (a box hugs its letters, and
     * "сеть" is half as tall as "Привет"), but only up to this factor, which
     * is the difference between two typefaces.
     */
    private val fontBias: Float by lazy {
        val lines = result.paragraphs.filter { it.writingDirection != WritingDirection.TopToBottom }.flatMap { it.lines }
            .filter { line -> line.box != null && line.text.count { !it.isWhitespace() } >= EM_MIN_LETTERS + 2 }
        val step = max(1, lines.size / BIAS_SAMPLES)
        val ratios = ArrayList<Float>()
        for (i in lines.indices step step) {
            val line = lines[i]
            val box = line.box!!.toPixels(width, height)
            val ratio = widthRatio(line.text.trim(), rtl = false)
            if (ratio > 0f && box.width >= 1f && box.height >= 1f) ratios += (box.height / inkRatio(line.text)) / (box.width / ratio)
        }
        if (ratios.isEmpty()) 1f else median(ratios).coerceIn(0.7f, 1.4f)
    }

    /** The size of the type a source line was set in, in image pixels. */
    private fun em(line: BlockLine, columns: Boolean): Float = ems.getOrPut(line) {
        val box = line.box ?: return@getOrPut 0f
        if (columns) return@getOrPut columnEm(box, line.source)
        val byHeight = box.height / inkRatio(line.source)
        val source = line.source.trim()
        if (source.count { !it.isWhitespace() } < EM_MIN_LETTERS) return@getOrPut byHeight
        val ratio = widthRatio(source, rtl = false)
        if (!(ratio > 0f)) return@getOrPut byHeight
        (box.width / ratio * fontBias).coerceIn(byHeight * EM_BELOW, byHeight * EM_ABOVE)
    }

    private fun em(block: Block): Float = median(block.lines.filter { it.box != null }.map { em(it, block.columns) })

    /** Whether the block's lines are one text size, judged by the lines long enough to judge by. */
    private fun sameSize(block: Block): Boolean {
        val judged = block.lines.filter { line ->
            line.box != null && (block.columns || line.source.count { !it.isWhitespace() } >= EM_MIN_LETTERS)
        }
        val sizes = if (judged.size >= 2) {
            judged.map { em(it, block.columns) }
        } else {
            block.boxes.map { if (block.columns) it.width else it.height }
        }
        return sizes.isNotEmpty() && sizes.max() <= sizes.min() * SAME_SIZE
    }

    /** How much larger than the source's type the translation is set, to look the size it was. */
    private fun match(block: Block): Float {
        var wide = 0
        var letters = 0
        for (line in block.lines) {
            for (char in line.source) {
                if (char.isWhitespace()) continue
                letters++
                if (isUpright(char.code)) wide++
            }
        }
        return if (wide * 2 > letters && !isCjkLanguage(block.target)) CJK_MATCH else 1f
    }

    /**
     * Whether a paragraph's lines read as running text rather than a sign, a
     * list or a bubble: one size, a common starting edge (but for an indent),
     * an ordinary line spacing, wide enough to be sentences, and every line
     * but the last full, which is what wrapping leaves behind and what the
     * items of a list are not.
     */
    private fun looksLikeProse(block: Block): Boolean {
        val lines = block.lines.filter { it.box != null }
        if (lines.size < 2 || !sameSize(block)) return false
        val size = em(block)
        if (!(size > 0f)) return false
        val boxes = lines.map { it.box!! }
        val c = cos(boxes[0].rotation)
        val s = sin(boxes[0].rotation)
        // Each line along the reading direction, from where it starts to where it ends, and its place across it.
        val starts = boxes.map { b ->
            val along = b.centerX * c + b.centerY * s
            if (block.rtl) -(along + b.width / 2) else along - b.width / 2
        }
        val ends = boxes.mapIndexed { i, b -> starts[i] + b.width }
        val column = ends.max() - starts.min()
        if (column < (if (lines.size == 2) PROSE_PAIR_WIDTH else PROSE_MIN_WIDTH) * size) return false
        val edge = median(starts)
        val off = starts.count { abs(it - edge) > EDGE_TOLERANCE * size }
        if (off > 1 || (off == 1 && lines.size < 3)) return false
        val order = lines.indices.sortedBy { -boxes[it].centerX * s + boxes[it].centerY * c }
        for (k in 1 until order.size) {
            val a = boxes[order[k - 1]]
            val b = boxes[order[k]]
            val step = (-b.centerX * s + b.centerY * c) - (-a.centerX * s + a.centerY * c)
            if (step < MIN_SPACING * size || step > MAX_SPACING * size) return false
        }
        val far = ends.max()
        for (k in 0 until order.size - 1) {
            val next = lines[order[k + 1]]
            val word = firstWordWidth(next) ?: continue
            if (ends[order[k]] + size * 0.25f + word <= far - WRAP_SLACK * size) return false
        }
        return true
    }

    /** How wide the first word of a source line is, from its own box or by its share of the line's letters. */
    private fun firstWordWidth(line: BlockLine): Float? {
        val source = result.paragraphs.getOrNull(line.paragraph)?.lines?.getOrNull(line.line) ?: return null
        val first = source.words.firstOrNull() ?: return null
        first.box?.let { return it.width * width }
        val letters = source.text.length
        val box = line.box ?: return null
        return if (letters > 0) box.width * first.text.length / letters else null
    }

    /** Whether a block of vertical columns is a page of prose rather than a bubble. */
    private fun looksLikeProseColumns(block: Block): Boolean {
        val boxes = block.boxes
        if (boxes.size < PROSE_COLUMNS) return false
        return median(boxes.map { it.height / max(1f, it.width) }) >= PROSE_COLUMN_LENGTH
    }

    private inner class Tone(private val block: Block) {
        /** The bubble's own colour, when the picture gave one: truer than what Lens reports. */
        var measured: Int? = null

        val background: Int by lazy {
            measured
                ?: block.lines.firstNotNullOfOrNull { it.style?.backgroundColor }?.let(::opaque)
                ?: (block.box ?: block.boxes.firstOrNull())?.let(::sampleAround)
                ?: ARGB_WHITE
        }

        fun background(line: BlockLine): Int = line.style?.backgroundColor?.let(::opaque) ?: background

        fun ink(line: BlockLine): Ink = Ink(resolveTextColor(line.style?.textColor, background(line)), background(line))

        /** The ink of the block set as one piece. */
        fun ink(): Ink {
            val styled = block.lines.firstOrNull { it.style?.textColor != null }?.style
            return Ink(resolveTextColor(styled?.textColor, background), background)
        }
    }

    private fun draft(block: Block): List<Draft> {
        val box = block.box
        val tone = Tone(block)
        val vertical = block.vertical
        val textLines = block.lines.filter { it.box != null && it.text.isNotEmpty() }

        // Horizontal text cannot sensibly occupy a tall column, and a paragraph
        // whose lines carry no text of their own can only be set as a whole.
        var reflow = box != null && !vertical &&
            (block.columns || style.layout == TranslationLayout.Reflow || !block.hasLineText)
        // The reflow layout says the page is bubbles; otherwise the paragraph has to.
        var bubble = style.layout == TranslationLayout.Reflow
        // Lines that find their own room never run into each other, so only
        // the layout that grows them blindly needs rescuing from that.
        val blind = style.growFromLineStart == LayoutChoice.Off
        if (!reflow && !vertical && box != null && style.reflowWhenCrowded && minFontPx > 0f) {
            // Index for index; a line with no text cannot be raised past anything.
            val rects = ArrayList<Rect>()
            val fitted = ArrayList<Float>()
            for (line in block.lines) {
                val lineBox = line.box ?: continue
                rects += lineBox.bounds()
                fitted += if (line.text.isEmpty()) minFontPx else fitLineSize(lineBox, line.text, block.rtl) * style.textScale
            }
            if (rects.size >= 2) {
                // A column of short centred lines whose translation only reaches a
                // readable size by leaving its boxes is a bubble being read as a list.
                if (isBubbleColumn(rects, box.bounds())) {
                    if (crowdsAtFloor(fitted, minFontPx)) {
                        reflow = true
                        bubble = true
                    }
                } else if (blind && collidesAtFloor(rects, fitted, minFontPx)) {
                    // Whatever it is, lines written through each other are never right.
                    reflow = true
                }
            }
        }

        // Running text: the paragraph as one block on the source's own lines,
        // not Lens's uneven split of it over them.
        var prose = false
        if (!vertical && box != null && style.reflowDenseParagraphs != LayoutChoice.Off) {
            prose = when {
                block.columns -> looksLikeProseColumns(block)
                !block.hasLineText -> false
                // On a page of bubbles only what plainly is running text is set as such.
                style.layout == TranslationLayout.Reflow -> looksLikeProse(block)
                // A crowded column of short centred lines was just found to be a bubble.
                reflow -> false
                style.reflowDenseParagraphs == LayoutChoice.On -> block.boxes.size >= DENSE_PARAGRAPH_LINES
                else -> looksLikeProse(block)
            }
        }

        val area = if (reflow && !prose && style.bubbleShape) bubbleArea(block, bubble) else null
        area?.color?.let { tone.measured = it }
        erase(block, tone, whole = reflow || prose, within = area?.inside)

        return when {
            prose -> listOfNotNull(proseDraft(block, tone))
            reflow -> listOfNotNull(bubbleDraft(block, tone, area))
            vertical -> listOf(
                Draft(block, NO_FAMILY, 0f, 0, "columns", { Fitted(0f) }) {
                    for (line in textLines) column(block.index, line.box!!, line.text, block.alignment, tone.ink(line))
                },
            )
            blind -> listOfNotNull(blindLinesDraft(block, tone, textLines))
            else -> lineDrafts(block, tone, textLines)
        }
    }

    /**
     * What each draft may be set at, at most: text that was one size in the
     * source is one size on the page.
     *
     * Drafts of one family whose source type is one size are a class. Fitted
     * alone they come out anywhere between cramped and roomy, by how much
     * longer each translation is and how much room it happens to have; a
     * typesetter picks one size for all of them. The class's size is what its
     * smaller members can have, but never under [CLASS_GIVE] of the middle
     * one, nor under the readable floor where the middle one reaches it: one
     * cramped bubble sets its own size, not the page's.
     */
    private fun sharedSizes(drafts: List<Draft>): FloatArray {
        val caps = FloatArray(drafts.size) { Float.MAX_VALUE }
        for (family in intArrayOf(FAMILY_BUBBLE, FAMILY_TEXT)) {
            // Orange option names and grey small print of one size are not one voice.
            val colours = ArrayList<Int>()
            val byColour = ArrayList<ArrayList<Int>>()
            for (i in drafts.indices) {
                val draft = drafts[i]
                if (draft.family != family || !(draft.em > 0f)) continue
                var group = colours.indexOfFirst { colourGap(it, draft.colour) <= CLASS_COLOUR }
                if (group < 0) {
                    group = colours.size
                    colours += draft.colour
                    byColour += ArrayList<Int>()
                }
                byColour[group] += i
            }
            val classes = ArrayList<List<Int>>()
            for (group in byColour) {
                val members = group.sortedBy { drafts[it].em }
                var start = 0
                while (start < members.size) {
                    var end = start + 1
                    while (end < members.size &&
                        drafts[members[end]].em <= drafts[members[end - 1]].em * CLASS_STEP &&
                        drafts[members[end]].em <= drafts[members[start]].em * CLASS_SPAN
                    ) {
                        end++
                    }
                    classes += members.subList(start, end)
                    start = end
                }
            }
            for (members in classes) {
                if (members.size < 2) continue
                val sizes = members.map { drafts[it].natural }.sorted()
                val middle = sizes[sizes.size / 2]
                var shared = max(sizes[(sizes.size - 1) / 4], middle * CLASS_GIVE)
                // Where the class reads at the floor, a cramped member does not take it below.
                if (minFontPx > 0f && middle >= minFontPx) shared = max(shared, minFontPx)
                for (i in members) caps[i] = shared
            }
            // The readable floor raises small print where there is room for
            // it, but never past what was larger in the source: a footnote
            // set bigger than the text it belongs to is not more readable,
            // only wrong.
            var above = Float.MAX_VALUE
            for (members in classes.sortedByDescending { members -> median(members.map { drafts[it].em }) }) {
                for (i in members) {
                    val draft = drafts[i]
                    if (min(draft.natural, caps[i]) > draft.plain) caps[i] = min(caps[i], max(above, draft.plain))
                }
                above = min(above, median(members.map { min(drafts[it].natural, caps[it]) }))
            }
        }
        return caps
    }

    private fun colourGap(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    // ---- erasing -----------------------------------------------------------

    /**
     * Removes the block's source text.
     *
     * @param whole whether every line goes, even one that carries no text of
     *   its own: the block is set as one piece.
     * @param within the inside of the bubble the block stands in, if the
     *   picture showed one: a fill stops at it.
     */
    private fun erase(block: Block, tone: Tone, whole: Boolean, within: Path?) {
        when (style.erase) {
            EraseMode.Fill -> eraseHull(block.boxes.ifEmpty { listOfNotNull(block.box) }, tone.background, untranslated, within)
            EraseMode.ServerPatch -> for (line in block.lines) {
                val box = line.box ?: continue
                val patch = line.style?.background
                val bitmap = state.patch(line.paragraph, line.line)
                if (patch != null && bitmap != null) {
                    erases += PatchOp(patchBox(box, patch.horizontalPadding, patch.verticalPadding), bitmap)
                } else if (whole || line.text.isNotEmpty()) {
                    erases += RectFillOp(box.inflate(2f, 1f), Color(tone.background(line)))
                }
            }
        }
    }

    private fun eraseHull(boxes: List<OrientedBox>, color: Int, keepOut: List<Rect>, within: Path?) {
        if (boxes.isEmpty()) return
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
        erases += HullFillOp(path, Color(color), padding, keepOut.filter { it.overlaps(reach) }, within)
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

    private fun textStyle(
        size: Float,
        rtl: Boolean,
        color: Color,
        align: TextAlign,
        outlineWidth: Float,
        pitch: Float,
        indent: Float,
    ) = TextStyle(
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
        // A pitch of its own puts every line where a source line was, with
        // the letters in the middle of it.
        lineHeight = if (pitch > 0f) pitch.sp else TextUnit.Unspecified,
        lineHeightStyle = if (pitch > 0f) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) else null,
        textIndent = if (indent != 0f) TextIndent(firstLine = indent.sp) else null,
        drawStyle = if (outlineWidth > 0f) {
            Stroke(outlineWidth, join = StrokeJoin.Round, cap = StrokeCap.Round)
        } else {
            null
        },
    )

    /** What a probe was measured with: everything that changes its layout. */
    private data class ProbeKey(
        val text: String,
        val size: Float,
        val rtl: Boolean,
        val wrap: Float,
        val align: TextAlign,
        val pitch: Float,
        val indent: Float,
        val locale: String?,
    )

    private val probes = HashMap<ProbeKey, TextLayoutResult>()

    /**
     * Lays [text] out at [size]: on one line, or wrapped to [wrapWidth] when
     * that is given. Overflow stays visible, because a size raised by the
     * readable-size floor is allowed to leave its box.
     *
     * A layout in plain black with no outline is a probe, made only to be
     * measured, and fitting asks for the same probe again and again: those
     * are kept.
     */
    private fun measure(
        text: String,
        size: Float,
        rtl: Boolean,
        color: Color = Color.Black,
        wrapWidth: Float? = null,
        align: TextAlign = TextAlign.Start,
        outlineWidth: Float = 0f,
        pitch: Float = 0f,
        indent: Float = 0f,
    ): TextLayoutResult {
        val key = if (color == Color.Black && outlineWidth == 0f) {
            ProbeKey(text, size, rtl, wrapWidth ?: -1f, align, pitch, indent, localeTag)
        } else {
            null
        }
        key?.let { probes[it] }?.let { return it }
        val layout = measurer.measure(
            text = text,
            style = textStyle(size, rtl, color, align, outlineWidth, pitch, indent),
            overflow = TextOverflow.Visible,
            softWrap = wrapWidth != null,
            maxLines = if (wrapWidth != null) Int.MAX_VALUE else 1,
            constraints = if (wrapWidth != null) Constraints.fixedWidth(max(1, ceil(wrapWidth).toInt())) else Constraints(),
        )
        if (key != null) probes[key] = layout
        return layout
    }

    /** How wide [text] runs on one line, per pixel of font size. Width is linear in the size (see [textStyle]). */
    private fun widthRatio(text: String, rtl: Boolean): Float =
        measure(text, REFERENCE_SIZE, rtl).multiParagraph.maxIntrinsicWidth / REFERENCE_SIZE

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

    // ---- lines that find their own room --------------------------------------

    /**
     * A line being fitted: its box, what is erased around it, how wide its
     * text runs, how large its type was, and which way it is set.
     */
    private class LineFit(
        val line: BlockLine,
        val box: OrientedBox,
        val erased: OrientedBox,
        /** The text's width per pixel of font size. */
        val ratio: Float,
        val em: Float,
        /** Where the text stands in its box while it fits it... */
        val align: Side,
        /** ...and the side it stays on once it is wider than the box. */
        val grow: Side,
    )

    /** Every line set by [lineDrafts], by paragraph and line: lines that face each other across a gap share it. */
    private val lineFits = HashMap<Long, LineFit>()

    private fun lineKey(paragraph: Int, line: Int): Long = (paragraph.toLong() shl 32) or (line.toLong() and 0xFFFFFFFFL)

    /** Where a line's text starts along its box, from the box's middle, when it is [textWidth] wide. */
    private fun startOf(fit: LineFit, textWidth: Float): Float =
        when (if (textWidth <= fit.box.width) fit.align else fit.grow) {
            Side.Left -> -fit.box.width / 2
            Side.Right -> fit.box.width / 2 - textWidth
            Side.Center -> -textWidth / 2
        }

    /**
     * Whether the picture shows nothing drawn in `ua..ub` by `va..vb` of
     * [box]'s own frame. Asked in slices along the box: a tilted line's
     * bounding box would take in the lines above and below it. Where the
     * picture cannot be read nothing is known to be empty.
     */
    private fun plainIn(box: OrientedBox, ua: Float, ub: Float, va: Float, vb: Float): Boolean {
        if (ub - ua < 0.5f || vb - va < 0.5f) return true
        val map = detail ?: return false
        val c = cos(box.rotation)
        val s = sin(box.rotation)
        val v = (va + vb) / 2
        val halfV = (vb - va) / 2
        var from = ua
        while (from < ub) {
            val to = min(ub, from + CLEAR_SLICE)
            val u = (from + to) / 2
            val halfU = (to - from) / 2
            val x = box.centerX + u * c - v * s
            val y = box.centerY + u * s + v * c
            val halfX = halfU * abs(c) + halfV * abs(s)
            val halfY = halfU * abs(s) + halfV * abs(c)
            if (!map.plain(Rect(x - halfX, y - halfY, x + halfX, y + halfY))) return false
            from = to
        }
        return true
    }

    /** This box in [frame]'s own coordinates, as the upright rectangle that holds it there. */
    private fun OrientedBox.seenFrom(frame: OrientedBox): Rect {
        val local = corners().map { frame.toLocal(it) }
        return Rect(local.minOf { it.x }, local.minOf { it.y }, local.maxOf { it.x }, local.maxOf { it.y })
    }

    /**
     * Whether a line's text at [size] is clear of everything: inside the
     * picture, off every other line of the page and off where that line's own
     * text will run, and, where it leaves what is erased for it, on nothing
     * the picture shows to be drawn.
     */
    private fun clearAt(fit: LineFit, size: Float): Boolean {
        val box = fit.box
        val textWidth = fit.ratio * size
        val u0 = startOf(fit, textWidth)
        val u1 = u0 + textWidth
        val up = size * INK_UP
        val down = size * INK_DOWN
        val ownU = fit.erased.width / 2
        val ownV = fit.erased.height / 2
        if (u0 >= -ownU && u1 <= ownU && up <= ownV && down <= ownV) return true

        val c = cos(box.rotation)
        val s = sin(box.rotation)
        fun toImage(u: Float, v: Float) = Offset(box.centerX + u * c - v * s, box.centerY + u * s + v * c)
        val corners = listOf(toImage(u0, -up), toImage(u1, -up), toImage(u1, down), toImage(u0, down))
        if (corners.any { it.x < 0f || it.y < 0f || it.x > width || it.y > height }) return false

        val area = Rect(u0 - CLEAR_GAP, -up - CLEAR_GAP, u1 + CLEAR_GAP, down + CLEAR_GAP)
        val around = Rect(
            corners.minOf { it.x } - CLEAR_GAP, corners.minOf { it.y } - CLEAR_GAP,
            corners.maxOf { it.x } + CLEAR_GAP, corners.maxOf { it.y } + CLEAR_GAP,
        )
        for (other in occupied) {
            if (other.paragraph == fit.line.paragraph && other.line == fit.line.line) continue
            if (other.bounds.overlaps(around) && other.box.seenFrom(box).overlaps(area)) return false
            // Where that line's own text will run, were it set as large
            // against its type as this one is against its own: two lines that
            // face each other across a gap (a dish and its price) then come
            // out the same size, each with its share of the gap, whichever is
            // fitted first.
            val theirs = lineFits[lineKey(other.paragraph, other.line)] ?: continue
            val theirSize = if (fit.em > 0f && theirs.em > 0f) size * theirs.em / fit.em else size
            val theirWidth = theirs.ratio * theirSize
            if (theirWidth <= theirs.box.width) continue
            val along = startOf(theirs, theirWidth) + theirWidth / 2
            val across = theirSize * (INK_DOWN - INK_UP) / 2
            val tc = cos(theirs.box.rotation)
            val ts = sin(theirs.box.rotation)
            val run = OrientedBox(
                theirs.box.centerX + along * tc - across * ts,
                theirs.box.centerY + along * ts + across * tc,
                theirWidth,
                theirSize * (INK_UP + INK_DOWN),
                theirs.box.rotation,
            )
            if (run.bounds().overlaps(around) && run.seenFrom(box).overlaps(area)) return false
        }

        // Past its end, before its start, above and below what is erased.
        val halo = box.height * HALO
        return plainIn(box, max(u0, ownU + halo), u1, -up, down) &&
            plainIn(box, u0, min(u1, -ownU - halo), -up, down) &&
            plainIn(box, max(u0, -ownU), min(u1, ownU), -up, -ownV - halo) &&
            plainIn(box, max(u0, -ownU), min(u1, ownU), ownV + halo, down)
    }

    /** The largest size up to [ceiling] at which the line's text is clear ([clearAt]). */
    private fun largestClear(fit: LineFit, ceiling: Float): Float {
        val top = max(MIN_FONT_PX, snap(ceiling))
        if (!(fit.ratio > 0f)) return top
        // Inside what is erased for it there is nothing to ask.
        val inside = min(fit.box.width / fit.ratio, fit.erased.height / (2 * INK_DOWN))
        if (top <= inside) return top
        val low = max(MIN_FONT_PX, snap(inside))
        return fitFontSize(low, top) { it <= low || clearAt(fit, it) }
    }

    /**
     * A paragraph set line by line, each line in the place of the source line
     * it replaces, at the size the source's type was.
     *
     * A translation is rarely as long as what it replaces. Where it is longer
     * the line runs on past its box, from the side the paragraph is aligned
     * to, into room the picture shows to be empty, and only shrinks when
     * there is none. Where the type is too small to read it is raised to the
     * readable floor on the same terms: as far as there is room, never over
     * anything.
     */
    private fun lineDrafts(block: Block, tone: Tone, textLines: List<BlockLine>): List<Draft> {
        if (textLines.isEmpty()) return emptyList()
        val align = block.alignment.side(block.rtl)
        // Auto: a line grows away from the side its paragraph is aligned to, and to both sides when centred.
        val grow = if (style.growFromLineStart == LayoutChoice.On) (if (block.rtl) Side.Right else Side.Left) else align
        val uniform = textLines.size >= 2 && when (style.uniformParagraphSize) {
            LayoutChoice.Off -> false
            LayoutChoice.On -> true
            LayoutChoice.Auto -> sameSize(block)
        }
        val shared = em(block)
        val fits = textLines.map { line ->
            val box = line.box!!
            val patch = line.style?.background?.takeIf { style.erase == EraseMode.ServerPatch && state.patch(line.paragraph, line.line) != null }
            val erased = if (patch != null) patchBox(box, patch.horizontalPadding, patch.verticalPadding) else box.inflate(2f, 1f)
            val type = if (uniform) shared else em(line, columns = false)
            LineFit(line, box, erased, widthRatio(line.text, block.rtl), type, align, grow)
                .also { lineFits[lineKey(line.paragraph, line.line)] = it }
        }
        val looks = match(block) * style.textScale
        fun sized(fit: LineFit): Fitted {
            val wanted = fit.em * looks
            val plain = largestClear(fit, wanted)
            // The readable floor, unless what is around the line already holds it back.
            val held = plain < max(MIN_FONT_PX, snap(wanted))
            return Fitted(if (minFontPx > wanted && !held) max(plain, largestClear(fit, minFontPx)) else plain, plain)
        }

        fun emit(fit: LineFit, size: Float) {
            val text = fit.line.text
            val probe = measure(text, size, block.rtl)
            val topLeft = Offset(fit.box.centerX + startOf(fit, probe.size.width.toFloat()), fit.box.centerY - probe.size.height / 2f)
            add(block.index, fit.box, topLeft, size, tone.ink(fit.line)) { color, outline ->
                measure(text, size, block.rtl, color, outlineWidth = outline)
            }
        }
        if (uniform) {
            // One size for the whole paragraph: the largest every line has room for.
            val all by lazy(LazyThreadSafetyMode.NONE) { fits.map(::sized) }
            return listOf(
                Draft(
                    block, FAMILY_TEXT, shared, tone.ink(fits[0].line).text, "lines",
                    { Fitted(all.minOf { it.size }, all.minOf { it.plain }) },
                ) { cap ->
                    val size = min(all.minOf { it.size }, snapCap(cap))
                    fits.forEach { emit(it, size) }
                },
            )
        }
        return fits.map { fit ->
            val own by lazy(LazyThreadSafetyMode.NONE) { sized(fit) }
            Draft(block, FAMILY_TEXT, fit.em, tone.ink(fit.line).text, "line", { own }) { cap -> emit(fit, min(own.size, snapCap(cap))) }
        }
    }

    private fun snapCap(cap: Float): Float = if (cap >= Float.MAX_VALUE / 2) cap else max(MIN_FONT_PX, snap(cap))

    // ---- Chromium layout: one line in its own box ---------------------------

    /**
     * calculateFontSizePixels(): the largest size at which [text] stays on one
     * line inside [box]. The height test uses the font's line box, not these
     * glyphs' ink, and "equal" already fails, which leaves slack.
     *
     * Width and height both grow with the size, so one measurement says about
     * where the answer is, and the search starts there.
     */
    private fun fitLineSize(box: OrientedBox, text: String, rtl: Boolean): Float {
        fun fits(size: Float): Boolean {
            val probe = measure(text, size, rtl)
            return probe.size.width < box.width && probe.size.height < box.height
        }
        val low = ceil(MIN_FONT_PX / FONT_STEP_PX).toInt()
        val high = max(low, floor(max(MIN_FONT_PX, box.height) / FONT_STEP_PX).toInt())
        val reference = measure(text, REFERENCE_SIZE, rtl)
        val perWidth = reference.multiParagraph.maxIntrinsicWidth / REFERENCE_SIZE
        val perHeight = reference.size.height / REFERENCE_SIZE
        val guess = min(if (perWidth > 0f) box.width / perWidth else Float.MAX_VALUE, if (perHeight > 0f) box.height / perHeight else Float.MAX_VALUE)
        var steps = floor(min(guess, high * FONT_STEP_PX) / FONT_STEP_PX).toInt().coerceIn(low, high)
        while (steps > low && !fits(steps * FONT_STEP_PX)) steps--
        while (steps < high && fits((steps + 1) * FONT_STEP_PX)) steps++
        return steps * FONT_STEP_PX
    }

    /** Whether the lines are about one text size by their boxes alone: their thickness varies by less than [SAME_SIZE]. */
    private fun similarHeights(boxes: List<OrientedBox>): Boolean =
        boxes.isNotEmpty() && boxes.maxOf { it.height } <= boxes.minOf { it.height } * SAME_SIZE

    /**
     * Chromium's own layout, each line fitted to its box and grown from its
     * middle when the readable floor raises it: what
     * [OverlayStyle.growFromLineStart] being off asks for.
     */
    private fun blindLinesDraft(block: Block, tone: Tone, textLines: List<BlockLine>): Draft? {
        if (textLines.isEmpty()) return null
        val fits = textLines.map { fitLineSize(it.box!!, it.text, block.rtl) }
        // One size for the whole paragraph: the largest every line fits at.
        val wanted = fits.size >= 2 && when (style.uniformParagraphSize) {
            LayoutChoice.Off -> false
            LayoutChoice.On -> true
            // Source lines of one size whose translations came out at visibly different sizes.
            LayoutChoice.Auto -> similarHeights(textLines.map { it.box!! }) && fits.max() > fits.min() * SIZE_JUMP
        }
        val shared = if (wanted) fits.min() else null
        val colour = tone.ink(textLines[0]).text
        return Draft(block, FAMILY_TEXT, block.lettering * SOURCE_SIZE_RATIO, colour, "boxed", { Fitted(shared ?: median(fits)) }) { cap ->
            textLines.forEachIndexed { i, line ->
                // A line the floor raises past its box gets a background
                // sized to it, unless a hull already covers the paragraph.
                val grownBackground = if (style.erase == EraseMode.ServerPatch) tone.background(line) else null
                blindLine(block.index, line.box!!, line.text, block.rtl, block.alignment, tone.ink(line), grownBackground, min(shared ?: fits[i], cap))
            }
        }
    }

    /**
     * @param grownBackground the colour to paint behind text the floor has
     *   raised past its box, or null to paint nothing there.
     * @param fitted the size the line fits its box at (or its paragraph's).
     */
    private fun blindLine(
        paragraph: Int,
        box: OrientedBox,
        text: String,
        rtl: Boolean,
        alignment: TextAlignment,
        ink: Ink,
        grownBackground: Int?,
        fitted: Float,
    ) {
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
        val shift = nudgeInside(box, drawW, drawH, rtl)
        val area = box.copy(centerX = box.centerX + shift.x, centerY = box.centerY + shift.y, width = drawW, height = drawH)
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

    // ---- running text: the paragraph wrapped on the source's lines ----------

    /**
     * Where a paragraph of running text stood, in its own box's frame: how
     * wide, where its first line's middle was, how far apart its lines, how
     * far its first line was indented.
     */
    private class ProseFrame(
        val width: Float,
        val height: Float,
        /** Whether the source's lines are there to be followed; a paragraph of columns has none. */
        val ruled: Boolean,
        val count: Int,
        /** The first line's middle, down from the box's. */
        val first: Float,
        /** From the first line's middle to the last's. */
        val span: Float,
        val pitch: Float,
        /** The first line's indent, in font sizes of the source. */
        val indent: Float,
    )

    private fun proseFrame(block: Block, size: Float): ProseFrame {
        val box = block.box!!
        if (block.columns) return ProseFrame(box.width, box.height, false, 0, 0f, 0f, 0f, 0f)
        val lines = block.boxes.map { box.toLocal(it.center) to it }.sortedBy { it.first.y }
        val middles = lines.map { it.first.y }
        val pitch = if (middles.size >= 2) median(middles.zipWithNext { a, b -> b - a }) else lines[0].second.height * 1.3f
        val starts = lines.map { (centre, line) -> if (block.rtl) -(centre.x + line.width / 2) else centre.x - line.width / 2 }
        val rest = median(starts.drop(1))
        val indent = if (lines.size >= 3 && size > 0f && starts[0] - rest > 0.5f * size) (starts[0] - rest) / size else 0f
        return ProseFrame(box.width, box.height, true, lines.size, middles.first(), middles.last() - middles.first(), pitch, indent)
    }

    /**
     * How far below [box] the page is empty, up to [depth] pixels: still the
     * picture, no other paragraph, nothing drawn.
     */
    private fun freeBelow(block: Block, box: OrientedBox, depth: Float): Float {
        if (!(depth > 0f)) return 0f
        val halfW = box.width / 2
        val bottom = box.height / 2
        val halo = block.lettering * HALO
        val c = cos(box.rotation)
        val s = sin(box.rotation)
        val reach = box.bounds().inflate(depth + CLEAR_GAP)
        val near = occupied.filter { it.paragraph !in block.members && it.bounds.overlaps(reach) }
        val steps = 8
        var free = 0f
        for (k in 1..steps) {
            val to = bottom + depth * k / steps
            val ends = listOf(
                Offset(box.centerX - halfW * c - to * s, box.centerY - halfW * s + to * c),
                Offset(box.centerX + halfW * c - to * s, box.centerY + halfW * s + to * c),
            )
            if (ends.any { it.x < 0f || it.y < 0f || it.x > width || it.y > height }) break
            val area = Rect(-halfW, bottom + CLEAR_GAP, halfW, to + CLEAR_GAP)
            if (near.any { it.box.seenFrom(box).overlaps(area) }) break
            if (!plainIn(box, -halfW, halfW, bottom + halo, to)) break
            free = depth * k / steps
        }
        return free
    }

    /**
     * Running text set as one block in its paragraph's box: at the size of
     * the source's type, aligned as the source was, its first line where the
     * source's first line was and the rest at the source's pitch.
     *
     * A translation that takes more lines than the source had runs on below,
     * at the same pitch, where the page is empty there. Where it is not, the
     * text is set a little smaller to keep to the source's lines, or, when
     * that would cost too much of its size, leaded more tightly.
     */
    private fun proseDraft(block: Block, tone: Tone): Draft? {
        val text = block.text
        if (text.isEmpty()) return null
        val box = block.box!!
        val ink = tone.ink()
        val size = em(block)
        val frame = proseFrame(block, size)
        val target = max(MIN_FONT_PX, size * match(block) * style.textScale)
        // The room under the paragraph is only looked at when the text does not fit the source's lines.
        val extra = lazy(LazyThreadSafetyMode.NONE) {
            if (frame.ruled) max(0f, freeBelow(block, box, frame.pitch * PROSE_EXTRA_LINES) - frame.pitch * PROSE_GAP_LINES) else 0f
        }
        val natural by lazy(LazyThreadSafetyMode.NONE) { prosePlan(block, box, frame, extra, text, ink, target) }
        return Draft(block, FAMILY_TEXT, size, ink.text, "prose", { Fitted(natural.size) }) { cap ->
            (if (cap >= natural.size) natural else prosePlan(block, box, frame, extra, text, ink, snapCap(cap))).emit()
        }
    }

    private fun prosePlan(
        block: Block,
        box: OrientedBox,
        frame: ProseFrame,
        extra: Lazy<Float>,
        text: String,
        ink: Ink,
        cap: Float,
    ): Plan {
        val align = when (block.alignment) {
            TextAlignment.Start -> if (block.justified && frame.count >= 3) TextAlign.Justify else TextAlign.Start
            TextAlignment.End -> TextAlign.End
            TextAlignment.Center -> TextAlign.Center
        }
        fun lay(of: String, size: Float, pitch: Float = 0f, color: Color = Color.Black, outline: Float = 0f) = measure(
            of, size, block.rtl, color, wrapWidth = frame.width, align = align, outlineWidth = outline,
            pitch = pitch, indent = frame.indent * size,
        )
        // Lines stand at the source's own pitch while there are no more of
        // them than the source had, or than it had and the page has room for
        // below; more than that are pushed together into the same room.
        fun onLines(lines: Int): Boolean =
            lines <= frame.count || lines <= frame.count + floor(extra.value / frame.pitch).toInt()
        fun pitchFor(lines: Int): Float =
            if (lines < 2 || onLines(lines)) frame.pitch else (frame.span + extra.value) / (lines - 1)
        fun fits(size: Float, onSourceLines: Boolean): Boolean {
            val probe = lay(text, size)
            // The widest unbreakable word has to fit as well: text is shrunk
            // before a word is ever split across lines.
            if (probe.multiParagraph.minIntrinsicWidth > frame.width + 0.5f || splitWordAt(text, probe) != null) return false
            if (!frame.ruled) return probe.size.height <= frame.height + 0.5f
            val lines = probe.lineCount
            // On the source's own lines the source's leading is taken as it is.
            if (onLines(lines)) return frame.pitch >= LINE_SOURCE * size
            return !onSourceLines && pitchFor(lines) >= LINE_TIGHT * size
        }
        val top = max(MIN_FONT_PX, snap(cap))
        var size = top
        if (!fits(top, onSourceLines = true)) {
            val tight = if (fits(top, false)) top else fitFontSize(MIN_FONT_PX, top) { fits(it, false) }
            size = tight
            if (frame.ruled) {
                val kept = fitFontSize(MIN_FONT_PX, tight) { fits(it, true) }
                if (kept >= tight * GRID_KEEP && kept >= top * GRID_FLOOR && fits(kept, true)) size = kept
            }
        }
        // A word wider than the box even at the smallest size has to be broken
        // after all. It is then broken properly, with a hyphen.
        var shown = text
        var guard = 0
        while (guard++ < MAX_HYPHENS) {
            val split = splitWordAt(shown, lay(shown, size)) ?: break
            shown = hyphenateAt(shown, split)
        }
        val pitch = if (frame.ruled) max(pitchFor(lay(shown, size).lineCount), size) else 0f
        val x = box.centerX - frame.width / 2
        // A line of the layout is one pitch tall with its letters in the
        // middle, so the first one is centred on the source's first line.
        val y = if (frame.ruled) box.centerY + frame.first - pitch / 2 else box.frame.top
        return Plan(size) {
            add(block.index, box, Offset(x, y), size, ink) { color, outline -> lay(shown, size, pitch, color, outline) }
        }
    }

    // ---- bubbles: the paragraph wrapped into its balloon ---------------------

    /**
     * The area a bubble's text is set in.
     *
     * @param axis the vertical line its lines are centred on.
     * @param anchor the height the block is centred on, or NaN for the shape's middle.
     * @param drawn whether the shape was read off the picture, and not guessed.
     * @param color the bubble's own colour, when the picture gave it.
     * @param inside the bubble's inside as a path, when the picture gave it.
     */
    private class BubbleArea(
        val shape: TextShape,
        val axis: Float,
        val anchor: Float,
        val drawn: Boolean,
        val color: Int?,
        val inside: Path?,
    )

    /**
     * The bubble around [block]: its outline read from the picture; failing
     * that, for a block known to be a bubble ([known]), the ellipse inscribed
     * in its room grown by the square root of two: the smallest ellipse that
     * contains a box is that box grown by exactly that.
     */
    private fun bubbleArea(block: Block, known: Boolean): BubbleArea? {
        val box = block.box ?: return null
        // A mask is read along the image's axes, so only upright text gets one.
        if (abs(box.rotation) > UPRIGHT_RADIANS) return null
        val others = othersOf(block)
        val own = box.bounds()
        findBubble(own, block.boxes.map { it.bounds() }, others)?.let { return it }
        if (!known) return null
        val room = roomFor(own, others, max(style.reflowGrowth, SQRT2), width, height)
        val ellipse = ellipseShape(room.center.x, room.center.y, room.width / 2, room.height / 2)
        return BubbleArea(ellipse, box.centerX, Float.NaN, drawn = false, color = null, inside = null)
    }

    private fun bubbleDraft(block: Block, tone: Tone, area: BubbleArea?): Draft? {
        val text = block.text
        if (text.isEmpty()) return null
        val box = block.box!!
        val ink = tone.ink()
        // A line is about as thick as its glyphs are tall: across a column, down a row.
        val thickest = block.boxes.maxOfOrNull { if (block.columns) it.width else it.height }
        val sizeCap = thickest?.let { it * SOURCE_SIZE_RATIO } ?: Float.MAX_VALUE
        fun plan(cap: Float): Plan {
            val shaped = area?.let { shapedPlan(block, it, text, ink, cap) }
            // A bubble the picture shows is where the text goes, even where it
            // holds less than the paragraph's box grown: what it holds less by
            // is lettering Lens did not read, or the outline itself.
            if (shaped != null && area?.drawn == true) return shaped
            val rectangle = rectanglePlan(block, box, text, ink, sizeCap, cap)
            return if (shaped != null && shaped.size >= rectangle.size * SHAPE_MIN_GAIN) shaped else rectangle
        }
        val natural by lazy(LazyThreadSafetyMode.NONE) { plan(Float.MAX_VALUE) }
        val em = if (block.columns) block.columnType else block.lettering * SOURCE_SIZE_RATIO
        val kind = if (area?.drawn == true) "bubble" else if (area != null) "ellipse" else "box"
        return Draft(block, FAMILY_BUBBLE, em, ink.text, kind, { Fitted(natural.size) }) { cap ->
            (if (cap >= natural.size) natural else plan(snapCap(cap))).emit()
        }
    }

    /**
     * Lays the whole translation out as one wrapped block in a rectangle.
     *
     * @param sourceCap the largest font size the fit may reach: the size of the
     *   source's own glyphs. A short translation would otherwise fill a big
     *   bubble with text several times larger than what it replaces.
     * @param cap the size nothing may exceed, not even the readable floor: the
     *   block's class is set at it.
     */
    private fun rectanglePlan(block: Block, box: OrientedBox, text: String, ink: Ink, sourceCap: Float, cap: Float): Plan {
        val rtl = block.rtl
        val sizeCap = min(sourceCap, cap)
        val align = when (block.alignment) {
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
        var area = reflowArea(block, box, 1f)
        var fitted = fit(area)
        if (growth > 1f) {
            for (step in listOf(1f + (growth - 1f) / 2, growth)) {
                if (fitted >= sizeCap * 0.9f) break
                val candidate = reflowArea(block, box, step)
                val candidateFit = fit(candidate)
                if (candidateFit > fitted) {
                    area = candidate
                    fitted = candidateFit
                }
            }
        }
        var size = min(finalFontSize(fitted, style.textScale, minFontPx), max(MIN_FONT_PX, cap))
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
            add(block.index, area, Offset(x, y), size, ink, rewrapped = true) { color, outline ->
                measure(shown, size, rtl, color, wrapWidth = wrap, align = align, outlineWidth = outline)
            }
        }
    }

    /**
     * Sets the whole translation inside [area], each line as wide as the
     * bubble is at its height, or returns null when the text does not fit it
     * at any size, and the rectangle is used instead.
     */
    private fun shapedPlan(block: Block, area: BubbleArea, text: String, ink: Ink, cap: Float): Plan? {
        // Symmetric about the axis and smooth: lines centred on it cannot zig-zag.
        val shape = symmetricShape(area.shape, area.axis)
        // A line box's short side is the height of the lettering, whichever way
        // the source ran; columns say more by their length.
        val sourceSize = if (block.columns) block.columnType * INK_FULL else median(block.boxes.map { min(it.width, it.height) })
        val ceiling = min(cap, if (sourceSize > 0f) max(sourceSize * MAX_UPSCALE, minFontPx) else MAX_SHAPED_FONT_PX)
        val perCharacter = wrapsPerCharacter(block.target)
        val advance = { s: String, size: Float -> measure(s, size, block.rtl).multiParagraph.maxIntrinsicWidth }
        var fit = fitShaped(advance, text, shape, REFLOW_LINE_SPACING, perCharacter, ceiling, anchor = area.anchor) ?: return null
        // Below the floor the floor gives way rather than the shape. A text
        // scale under one asks for air: the largest fit no bigger than that.
        if (style.textScale < 1f) {
            fitShaped(advance, text, shape, REFLOW_LINE_SPACING, perCharacter, fit.size * style.textScale, anchor = area.anchor)
                ?.let { fit = it }
        }
        val size = fit.size
        val lines = fit.lines
        return Plan(size) { emitShaped(block.index, lines, size, area.axis, block.rtl, ink) }
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
    private fun findBubble(own: Rect, lines: List<Rect>, others: List<Rect>): BubbleArea? {
        val pad = max(own.width, own.height) * WINDOW_PAD
        if (!(pad > 0f) || lines.isEmpty()) return null
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
        val grid = readCells(readable, sx, sy, sw, sh, gw, gh) ?: return null
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

        // The text is centred on the strip every row it stood on has free. In
        // a plain bubble that is the bubble's middle; beside lettering Lens did
        // not read it is the middle of what that lettering leaves.
        val rowFrom = floor((lines.minOf { it.top } - sy) / scaleY).toInt()
        val rowTo = ceil((lines.maxOf { it.bottom } - sy) / scaleY).toInt()
        val strip = mask.commonSpan(rowFrom, rowTo)
        val axis = if (strip != null) {
            (sx + (strip.first + strip.second) / 2f * scaleX).coerceIn(own.left, own.right)
        } else {
            own.center.x
        }
        val inside = Path().apply {
            for (y in mask.top until mask.bottom) {
                val x = sx + mask.left[y] * scaleX
                if (y == mask.top) moveTo(x, sy + y * scaleY) else lineTo(x, sy + y * scaleY)
                lineTo(x, sy + (y + 1) * scaleY)
            }
            for (y in mask.bottom - 1 downTo mask.top) {
                val x = sx + mask.right[y] * scaleX
                lineTo(x, sy + (y + 1) * scaleY)
                lineTo(x, sy + y * scaleY)
            }
            close()
        }
        return BubbleArea(
            shape = maskShape(mask, sx.toFloat(), sy.toFloat(), scaleX, scaleY),
            axis = axis,
            anchor = own.center.y,
            drawn = true,
            color = mask.color,
            inside = inside,
        )
    }

    /**
     * The area a reflowed paragraph may use: its box, grown by [growth] (up to
     * [OverlayStyle.reflowGrowth]) where neighbours and the image leave room.
     * Height grows less than width (at most a fifth): bubbles are stacked more
     * tightly than they are packed side by side. A visibly rotated box is left
     * alone, since room is measured along the image's axes.
     */
    private fun reflowArea(block: Block, box: OrientedBox, growth: Float): OrientedBox {
        if (abs(box.rotation) > 0.05f) return box
        val own = box.frame
        val room = roomFor(own, othersOf(block), growth, width, height)
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

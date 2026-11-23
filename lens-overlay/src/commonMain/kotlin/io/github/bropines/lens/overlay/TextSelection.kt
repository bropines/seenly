package io.github.bropines.lens.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A run of selected words.
 *
 * Words are numbered in reading order across the whole [ScanResult]: paragraph
 * by paragraph, line by line, word by word, in the order Lens returns them
 * (which already accounts for right-to-left and vertical text). Both ends are
 * inclusive and [start] never exceeds [endInclusive]; use [between] to build a
 * range from two ends in either order.
 */
@Immutable
public data class WordRange(val start: Int, val endInclusive: Int) {
    init {
        require(start in 0..endInclusive) { "Need 0 <= start <= endInclusive, got $start..$endInclusive" }
    }

    /** How many words are selected; at least one. */
    val count: Int get() = endInclusive - start + 1

    public operator fun contains(word: Int): Boolean = word in start..endInclusive

    public companion object {
        /** The range from [a] to [b] whichever comes first, as when a selection is dragged backwards. */
        public fun between(a: Int, b: Int): WordRange = WordRange(min(a, b), max(a, b))
    }
}

/** Where a word sits in a [ScanResult]. */
internal data class WordRef(val paragraph: Int, val line: Int, val word: Int)

/** The words of a [ScanResult] in reading order. */
internal class WordIndex(val result: ScanResult) {
    val refs: List<WordRef> = buildList {
        result.paragraphs.forEachIndexed { p, paragraph ->
            paragraph.lines.forEachIndexed { l, line ->
                for (w in line.words.indices) add(WordRef(p, l, w))
            }
        }
    }
    val size: Int get() = refs.size

    private val paragraphStarts: IntArray = IntArray(result.paragraphs.size + 1).also { starts ->
        result.paragraphs.forEachIndexed { p, paragraph -> starts[p + 1] = starts[p] + paragraph.lines.sumOf { it.words.size } }
    }

    /** The words of paragraph [paragraph], or null when it is out of range or has none. */
    fun paragraphRange(paragraph: Int): WordRange? {
        if (paragraph !in result.paragraphs.indices) return null
        val start = paragraphStarts[paragraph]
        val end = paragraphStarts[paragraph + 1] - 1
        return if (end >= start) WordRange(start, end) else null
    }

    /** [range] cut down to the words that exist, or null when none of it does. */
    fun clamp(range: WordRange): WordRange? =
        if (size == 0 || range.start >= size) null else WordRange(range.start, min(range.endInclusive, size - 1))
}

/** How many words [ScanResult] holds, i.e. one past the largest index a [WordRange] can use. */
public val ScanResult.wordCount: Int get() = paragraphs.sumOf { paragraph -> paragraph.lines.sumOf { it.words.size } }

/** The words of paragraph [index] as a range, or null when it has no words or does not exist. */
public fun ScanResult.wordRangeOfParagraph(index: Int): WordRange? = WordIndex(this).paragraphRange(index)

/**
 * The text of the words in [range], as it should be copied or translated.
 *
 * Words are joined with the separator Lens reports after each (nothing when it
 * reports none, as between CJK characters), lines of a paragraph with a line
 * break, and paragraphs with a blank line. Nothing follows the last word.
 * Indices past the last word are ignored.
 */
public fun ScanResult.textOf(range: WordRange): String = textOf(WordIndex(this), range)

internal fun ScanResult.textOf(index: WordIndex, range: WordRange): String {
    val clamped = index.clamp(range) ?: return ""
    val out = StringBuilder()
    for (i in clamped.start..clamped.endInclusive) {
        val ref = index.refs[i]
        val word = paragraphs[ref.paragraph].lines[ref.line].words[ref.word]
        out.append(word.text)
        if (i == clamped.endInclusive) break
        val next = index.refs[i + 1]
        when {
            next.paragraph != ref.paragraph -> out.append("\n\n")
            next.line != ref.line -> out.append('\n')
            else -> out.append(word.separator.orEmpty())
        }
    }
    return out.toString()
}

// ---- geometry ----------------------------------------------------------------

/** Which way a line reads inside its own (unrotated) box. */
internal enum class LineFlow { LeftToRight, RightToLeft, TopToBottom }

/** The selected part of one line: a single rotated rectangle and the way the line reads. */
internal data class LineSelection(val box: OrientedBox, val flow: LineFlow)

/** One of the two ends of a selection. */
internal enum class SelectionEnd { Start, End }

/** The distance from [point] to this box; 0 inside it. */
internal fun OrientedBox.distanceTo(point: Offset): Float {
    val local = toLocal(point)
    val dx = max(0f, kotlin.math.abs(local.x) - width / 2)
    val dy = max(0f, kotlin.math.abs(local.y) - height / 2)
    return sqrt(dx * dx + dy * dy)
}

/** A point given relative to this box's centre in its unrotated frame, in image space. */
internal fun OrientedBox.fromLocal(local: Offset): Offset {
    val c = cos(rotation)
    val s = sin(rotation)
    return Offset(centerX + local.x * c - local.y * s, centerY + local.x * s + local.y * c)
}

/**
 * One rectangle around several word boxes of the same line.
 *
 * The rectangle takes the line's angle. Along the line it spans exactly the
 * words; across the line it takes the line's own thickness, so the selection
 * reads as one even strip whatever the height of the individual words, and
 * selections on neighbouring words of a line never show a seam. Without a
 * [line] box the first word's angle is used and both extents come from the words.
 */
internal fun mergeWordBoxes(line: OrientedBox?, words: List<OrientedBox>): OrientedBox? {
    if (words.isEmpty()) return line
    val frame = (line ?: words.first()).copy(width = 0f, height = 0f)
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (word in words) {
        for (corner in word.corners()) {
            val local = frame.toLocal(corner)
            minX = min(minX, local.x)
            maxX = max(maxX, local.x)
            minY = min(minY, local.y)
            maxY = max(maxY, local.y)
        }
    }
    if (line != null) {
        if (line.width >= line.height) {
            minY = -line.height / 2
            maxY = line.height / 2
        } else {
            minX = -line.width / 2
            maxX = line.width / 2
        }
    }
    val center = frame.fromLocal(Offset((minX + maxX) / 2, (minY + maxY) / 2))
    return OrientedBox(center.x, center.y, maxX - minX, maxY - minY, frame.rotation)
}

/**
 * Splits a line's box among its words in proportion to their length, for a
 * line whose words came without boxes of their own.
 */
internal fun splitLineBox(line: OrientedBox, lengths: List<Int>, flow: LineFlow): List<OrientedBox> {
    val total = lengths.sumOf { max(1, it) }.toFloat()
    val along = if (flow == LineFlow.TopToBottom) line.height else line.width
    var consumed = 0f
    return lengths.map { length ->
        val share = max(1, length) / total * along
        val middle = consumed + share / 2 - along / 2
        consumed += share
        val local = when (flow) {
            LineFlow.LeftToRight -> Offset(middle, 0f)
            LineFlow.RightToLeft -> Offset(-middle, 0f)
            LineFlow.TopToBottom -> Offset(0f, middle)
        }
        val center = line.fromLocal(local)
        if (flow == LineFlow.TopToBottom) {
            OrientedBox(center.x, center.y, line.width, share, line.rotation)
        } else {
            OrientedBox(center.x, center.y, share, line.height, line.rotation)
        }
    }
}

/**
 * Where a selection handle hangs: [anchor] is the corner of the selection it
 * points at and [center] the middle of its round part, both in image pixels;
 * [grip] is the middle of the text edge it stands for. [away] is the diagonal
 * from the anchor to the centre in the line's own frame, one unit per axis.
 */
internal data class HandlePlacement(
    val anchor: Offset,
    val center: Offset,
    val grip: Offset,
    val away: Offset,
    val rotationDegrees: Float,
)

/**
 * Places the handle for [end] of a selection whose first or last line is
 * [line]: below the start and end of a horizontal line (mirrored for
 * right-to-left), and at the top right and bottom left of a vertical one.
 */
internal fun handlePlacement(line: LineSelection, end: SelectionEnd, radius: Float): HandlePlacement {
    val hw = line.box.width / 2
    val hh = line.box.height / 2
    val start = end == SelectionEnd.Start
    // The corner, the diagonal away from the text, and the middle of the text's edge.
    val (anchor, away, grip) = when (line.flow) {
        LineFlow.LeftToRight ->
            if (start) Triple(Offset(-hw, hh), Offset(-1f, 1f), Offset(-hw, 0f)) else Triple(Offset(hw, hh), Offset(1f, 1f), Offset(hw, 0f))
        LineFlow.RightToLeft ->
            if (start) Triple(Offset(hw, hh), Offset(1f, 1f), Offset(hw, 0f)) else Triple(Offset(-hw, hh), Offset(-1f, 1f), Offset(-hw, 0f))
        LineFlow.TopToBottom ->
            if (start) Triple(Offset(hw, -hh), Offset(1f, -1f), Offset(0f, -hh)) else Triple(Offset(-hw, hh), Offset(-1f, 1f), Offset(0f, hh))
    }
    return HandlePlacement(
        anchor = line.box.fromLocal(anchor),
        center = line.box.fromLocal(anchor + away * radius),
        grip = line.box.fromLocal(grip),
        away = away,
        rotationDegrees = line.box.rotationDegrees,
    )
}

/**
 * Every word of a result as a box in the pixels of an image of the given
 * size, for hit-testing and for drawing a selection.
 */
internal class WordLayout(val result: ScanResult, imageWidth: Float, imageHeight: Float) {
    val index: WordIndex = WordIndex(result)

    private val lineBoxes = HashMap<Long, OrientedBox>()
    private val lineFlows = HashMap<Long, LineFlow>()

    /** A box per word, in reading order; null only when neither the word nor its line has one. */
    val wordBoxes: List<OrientedBox?>

    init {
        val boxes = ArrayList<OrientedBox?>(index.size)
        result.paragraphs.forEachIndexed { p, paragraph ->
            paragraph.lines.forEachIndexed { l, line ->
                val lineBox = line.box?.toPixels(imageWidth, imageHeight)
                val flow = when {
                    paragraph.writingDirection == WritingDirection.TopToBottom &&
                        (lineBox == null || lineBox.height > lineBox.width) -> LineFlow.TopToBottom
                    paragraph.writingDirection == WritingDirection.RightToLeft -> LineFlow.RightToLeft
                    else -> LineFlow.LeftToRight
                }
                lineFlows[key(p, l)] = flow
                if (lineBox != null) lineBoxes[key(p, l)] = lineBox
                val own = line.words.map { it.box?.toPixels(imageWidth, imageHeight) }
                if (lineBox != null && own.any { it == null }) {
                    boxes += splitLineBox(lineBox, line.words.map { it.text.length }, flow)
                } else {
                    boxes += own
                }
            }
        }
        wordBoxes = boxes
    }

    private fun key(paragraph: Int, line: Int): Long = (paragraph.toLong() shl 32) or line.toLong()

    /**
     * The word nearest to [point] (image pixels), or null when none lies
     * within [maxDistance]. A word that contains the point always wins.
     */
    fun nearestWord(point: Offset, maxDistance: Float = Float.MAX_VALUE): Int? {
        var best: Int? = null
        var bestDistance = maxDistance
        wordBoxes.forEachIndexed { i, box ->
            if (box == null) return@forEachIndexed
            val distance = box.distanceTo(point)
            if (distance < bestDistance || (distance == bestDistance && best == null)) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /** The selected part of every line [range] touches, in reading order: one rectangle per line. */
    fun lineSelections(range: WordRange): List<LineSelection> {
        val clamped = index.clamp(range) ?: return emptyList()
        val out = ArrayList<LineSelection>()
        var i = clamped.start
        while (i <= clamped.endInclusive) {
            val ref = index.refs[i]
            var last = i
            while (last < clamped.endInclusive && index.refs[last + 1].let { it.paragraph == ref.paragraph && it.line == ref.line }) last++
            val lineKey = key(ref.paragraph, ref.line)
            val line = lineBoxes[lineKey]
            val wholeLine = ref.word == 0 && index.refs[last].word == result.paragraphs[ref.paragraph].lines[ref.line].words.lastIndex
            val merged = if (wholeLine && line != null) line else mergeWordBoxes(line, (i..last).mapNotNull { wordBoxes[it] })
            if (merged != null) out += LineSelection(merged, lineFlows[lineKey] ?: LineFlow.LeftToRight)
            i = last + 1
        }
        return out
    }

    /** The upright rectangle around everything [range] selects, in image pixels. */
    fun bounds(range: WordRange): Rect? =
        lineSelections(range).map { it.box.bounds() }.reduceOrNull { a, b ->
            Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
        }
}

// ---- state -------------------------------------------------------------------

/**
 * The text selection of a [TranslatedImage] in [OverlayMode.TextBoxes].
 *
 * Hoist one with [rememberTextSelectionState] and pass it to [TranslatedImage]
 * to turn selection on: a tap selects a paragraph, a long press selects a word
 * and dragging on extends it, and the two handles move either end. Read
 * [selection] and [selectedText] to act on it, and [boundsInView] to place an
 * action bar next to it.
 *
 * The state follows the result its image shows: when that changes, or the
 * image leaves text mode, the selection is cleared.
 */
@Stable
public class TextSelectionState {
    /** The selected words, or null when nothing is selected. Observable. */
    public var selection: WordRange? by mutableStateOf(null)
        private set

    /**
     * Whether the user is dragging the selection right now (after a long
     * press, or by a handle). An action bar is best hidden meanwhile.
     */
    public var isDragging: Boolean by mutableStateOf(false)
        internal set

    private var result: ScanResult? by mutableStateOf(null)
    private var index: WordIndex? = null
    internal var layout: WordLayout? by mutableStateOf(null)
        private set
    private var fit: Rect by mutableStateOf(Rect.Zero)
    private var zoom: ZoomState? by mutableStateOf(null)

    /** The selected text, joined as [textOf] describes, or null when nothing is selected. */
    public val selectedText: String?
        get() {
            val range = selection ?: return null
            val scan = result ?: return null
            return scan.textOf(indexFor(scan), range)
        }

    /**
     * The upright rectangle around the selected text in the [TranslatedImage]'s
     * own coordinates (pixels from its top-left corner), or null when nothing
     * is selected or the image is not laid out yet.
     *
     * It follows zoom and pan: read it where the action bar is placed (for
     * example in `Modifier.offset { }`) and the bar moves with the image
     * without recomposing. It can lie partly or wholly outside the view when
     * the image is zoomed in, and the handles hang about 24 dp past it at the
     * selection's end, so leave room or place the bar above.
     */
    public val boundsInView: Rect?
        get() {
            val range = selection ?: return null
            val bounds = layout?.bounds(range) ?: return null
            if (fit.isEmpty) return null
            val topLeft = imageToView(bounds.topLeft)
            val bottomRight = imageToView(bounds.bottomRight)
            return Rect(topLeft, bottomRight)
        }

    /** Selects every word of [result]. */
    public fun selectAll(result: ScanResult) {
        adopt(result)
        val count = indexFor(result).size
        selection = if (count > 0) WordRange(0, count - 1) else null
    }

    /**
     * Selects the whole paragraph [index] of the result the image shows.
     *
     * @return false, leaving the selection alone, when there is no such
     *   paragraph, it has no words, or no result is shown yet.
     */
    public fun selectParagraph(index: Int): Boolean {
        val scan = result ?: return false
        val range = indexFor(scan).paragraphRange(index) ?: return false
        selection = range
        return true
    }

    /** Selects [range] of the result the image shows, cut down to the words that exist. */
    public fun select(range: WordRange) {
        val scan = result ?: return
        selection = indexFor(scan).clamp(range)
    }

    /** Selects nothing. */
    public fun clear() {
        selection = null
        isDragging = false
    }

    private fun indexFor(scan: ScanResult): WordIndex =
        index?.takeIf { it.result === scan } ?: WordIndex(scan).also { index = it }

    private fun adopt(scan: ScanResult?) {
        if (scan === result) return
        result = scan
        index = null
        selection = null
        isDragging = false
    }

    /** Called by the image on every composition: what it shows and where. */
    internal fun bind(layout: WordLayout?, imageWidth: Float, fit: Rect, zoom: ZoomState) {
        adopt(layout?.result)
        if (layout != null) index = layout.index
        this.layout = layout
        this.imageWidth = imageWidth
        this.fit = fit
        this.zoom = zoom
    }

    private var imageWidth: Float by mutableStateOf(0f)

    /** View pixels per image pixel with the image fitted, before any zoom. */
    private val fitScale: Float get() = if (imageWidth > 0f) fit.width / imageWidth else 0f

    /** View pixels per image pixel, zoom included; 0 until the image is laid out. */
    internal val viewScale: Float get() = fitScale * (zoom?.scale ?: 1f)

    internal fun imageToView(point: Offset): Offset {
        val unzoomed = fit.topLeft + point * fitScale
        val z = zoom ?: return unzoomed
        return z.offset + unzoomed * z.scale
    }

    internal fun viewToImage(point: Offset): Offset {
        val scale = fitScale
        if (scale <= 0f) return Offset.Zero
        val unzoomed = zoom?.toUnzoomed(point) ?: point
        return (unzoomed - fit.topLeft) / scale
    }

    /** Moves one end of the selection while the other stays at [fixed]. */
    internal fun dragTo(fixed: Int, moving: Int): Boolean {
        val range = WordRange.between(fixed, moving)
        if (range == selection) return false
        selection = range
        return true
    }
}

/** Creates and remembers a [TextSelectionState]. */
@Composable
public fun rememberTextSelectionState(): TextSelectionState = remember { TextSelectionState() }

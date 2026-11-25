package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// A speech bubble as a text area: whether a paragraph is one, what shape it
// has, and the largest type that shape holds. Ported from the userscript's
// render/bubble.ts, where the reasoning behind every constant is recorded.
//
// Lens reports a bubble as a column of short lines, each box hugging two or
// three words. A translation is longer per line, so fitting it line by line
// shrinks it, the readable-size floor puts the size back, and the lines, still
// pinned to boxes a pitch apart, land on top of each other. The bubble is one
// area, and its outline is in the picture.
//
// Everything here is arithmetic on numbers handed in, so it is tested without
// a graphics backend.

// ---- decision ------------------------------------------------------------------

/** A bubble is "several" lines; fewer is a caption, a button, a headline. */
internal const val COLUMN_MIN_LINES = 3

/** A line's width in line heights: a dozen is two to four words, which is what a bubble's line holds. */
internal const val COLUMN_MAX_LINE_ASPECT = 12f

/** The paragraph's width over its height. Three lines of a dozen heights each is a strip, not a column. */
internal const val COLUMN_MAX_ASPECT = 2.5f

/** Four lines or more may be wider: wide Western bubbles measured 2.7, a centred three-line banner 3. */
internal const val COLUMN_MAX_ASPECT_TALL = 3.2f
internal const val COLUMN_TALL_LINES = 4

/**
 * How far a line's centre may sit from the column's, as a share of the
 * paragraph's width. Bubble lines measure within 0.04; left-aligned text with
 * a short last line is off by 0.25 or more.
 */
internal const val COLUMN_MAX_OFF_CENTRE = 0.15f

private fun median(values: List<Float>): Float = values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

/**
 * Whether these line boxes are a bubble's column: several short lines, stacked
 * and centred on one axis. Geometry alone, and only half of the decision: a
 * column whose translation fits line by line is left as Chromium lays it out.
 */
internal fun isBubbleColumn(lines: List<Rect>, paragraph: Rect, minLines: Int = COLUMN_MIN_LINES): Boolean {
    if (lines.size < max(2, minLines)) return false
    val paraW = paragraph.width
    val paraH = paragraph.height
    val maxAspect = if (lines.size >= COLUMN_TALL_LINES) COLUMN_MAX_ASPECT_TALL else COLUMN_MAX_ASPECT
    if (!(paraW > 0f) || !(paraH > 0f) || paraW > maxAspect * paraH) return false

    val sorted = lines.sortedBy { it.top + it.bottom }
    for (i in 1 until sorted.size) {
        // Two boxes side by side are a table row, not a stack.
        if (sorted[i].top < sorted[i - 1].center.y) return false
    }
    if (median(sorted.map { it.width / max(1f, it.height) }) > COLUMN_MAX_LINE_ASPECT) return false
    val centres = sorted.map { it.center.x }
    val axis = median(centres)
    return centres.all { abs(it - axis) <= COLUMN_MAX_OFF_CENTRE * paraW }
}

/**
 * Whether laying the translation out line by line is about to go wrong: some
 * line's fitted size is under the readable floor. A fitted line is inside its
 * box by construction, so lines only collide once the floor raises one, and
 * "below the floor" is the half of that event that can be read off beforehand.
 */
internal fun crowdsAtFloor(fitted: List<Float>, floor: Float): Boolean = floor > 0f && fitted.any { it < floor }

/**
 * Whether the lines, raised to the floor where they need it, will be written
 * through each other: two neighbours (by centre height) closer than half their
 * two sizes added up. The net under [isBubbleColumn], whose geometry is
 * whatever Lens returned this time. [lines] and [fitted] are index for index.
 */
internal fun collidesAtFloor(lines: List<Rect>, fitted: List<Float>, floor: Float): Boolean {
    if (lines.size < 2 || lines.size != fitted.size || !crowdsAtFloor(fitted, floor)) return false
    val order = lines.indices.map { lines[it].center.y to max(fitted[it], floor) }.sortedBy { it.first }
    for (i in 1 until order.size) {
        val (aboveY, aboveSize) = order[i - 1]
        val (belowY, belowSize) = order[i]
        if (belowY - aboveY < (aboveSize + belowSize) / 2) return true
    }
    return false
}

// ---- room ------------------------------------------------------------------------

/** Never let two paragraphs share an edge; a pixel of daylight reads as one. */
internal const val BAND_GAP = 2f

/**
 * The userscript's `roomFor`: [own] grown by [growth], each side stopped by a
 * neighbour that shares the band on the perpendicular axis, and by the
 * picture. Used to find where a bubble may be looked for, where a neighbour in
 * the same band is the end of this paragraph's half of a shared bubble.
 */
internal fun bandRoom(own: Rect, others: List<Rect>, growth: Float, imageWidth: Float, imageHeight: Float): Rect {
    val growX = own.width * (growth - 1f) / 2
    val growY = own.height * (growth - 1f) / 2
    var left = max(0f, own.left - growX)
    var right = min(imageWidth, own.right + growX)
    var top = max(0f, own.top - growY)
    var bottom = min(imageHeight, own.bottom + growY)
    for (other in others) {
        if (other.bottom > own.top && other.top < own.bottom) {
            if (other.right <= own.left) left = max(left, other.right + BAND_GAP)
            if (other.left >= own.right) right = min(right, other.left - BAND_GAP)
        }
        if (other.right > own.left && other.left < own.right) {
            if (other.bottom <= own.top) top = max(top, other.bottom + BAND_GAP)
            if (other.top >= own.bottom) bottom = min(bottom, other.top - BAND_GAP)
        }
    }
    // A neighbour closer than the box itself would invert it; the box wins.
    return Rect(min(left, own.left), min(top, own.top), max(right, own.right), max(bottom, own.bottom))
}

// ---- mask ------------------------------------------------------------------------

/** Which edges of the search window are there only because the search had to stop somewhere. */
internal data class Sides(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean)

/** One span per row: `left[y] <= x < right[y]` for `top <= y < bottom`, in grid cells. */
internal class BubbleMask(
    val width: Int,
    val height: Int,
    val top: Int,
    val bottom: Int,
    val left: IntArray,
    val right: IntArray,
    /** The bubble's own colour, opaque: what its lettering is erased with. */
    val color: Int = ARGB_WHITE,
) {
    /**
     * The columns every row of `from until to` has in common, or null when
     * they share none. Rows the mask does not have are skipped.
     */
    fun commonSpan(from: Int, to: Int): Pair<Int, Int>? {
        var lo = Int.MIN_VALUE
        var hi = Int.MAX_VALUE
        for (y in max(from, top) until min(to, bottom)) {
            lo = max(lo, left[y])
            hi = min(hi, right[y])
        }
        return if (lo != Int.MIN_VALUE && hi > lo) lo to hi else null
    }
}

/**
 * How far a channel may sit from the bubble's colour and still be the bubble:
 * wide enough for compression and a faint gradient, narrow enough that an
 * anti-aliased glyph edge is on the other side. Compared with the bubble's
 * colour, never with the neighbouring pixel, so a gradient cannot walk out.
 */
internal const val BG_TOLERANCE = 32

/** The least share of the line boxes that has to be the background; text over artwork has none. */
internal const val MIN_BG_SHARE = 0.4f

/** Rows this much narrower than the widest are a tail, and text does not go in a tail. */
internal const val TAIL_SHARE = 0.3f

private class GridBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

private fun gridBox(rect: Rect, width: Int, height: Int) = GridBox(
    floor(rect.left).toInt().coerceIn(0, width),
    floor(rect.top).toInt().coerceIn(0, height),
    ceil(rect.right).toInt().coerceIn(0, width),
    ceil(rect.bottom).toInt().coerceIn(0, height),
)

/**
 * The bubble around some lines of text, read from opaque ARGB [pixels].
 *
 * The background is the commonest colour inside the line boxes, as a mode
 * over 16-level bins (they hug the glyphs, so what is not ink is bubble).
 * From those pixels a four-connected fill spreads over everything within
 * [BG_TOLERANCE] of that colour and stops at the outline. [blocked] is the
 * other paragraphs: a second paragraph in the same bubble is not room.
 *
 * Null when the picture shows no bubble: no dominant colour, the fill reached
 * an [open] edge (nothing held it in), or the result is larger than [maxArea].
 */
internal fun bubbleMask(
    pixels: IntArray,
    width: Int,
    height: Int,
    lines: List<Rect>,
    blocked: List<Rect>,
    open: Sides,
    maxArea: Float = Float.POSITIVE_INFINITY,
): BubbleMask? {
    if (width < 1 || height < 1 || pixels.size < width * height) return null
    val boxes = lines.map { gridBox(it, width, height) }.filter { it.x1 > it.x0 && it.y1 > it.y0 }
    if (boxes.isEmpty()) return null

    fun bin(p: Int): Int = (((p shr 20) and 0xF) shl 8) or (((p shr 12) and 0xF) shl 4) or ((p shr 4) and 0xF)
    val bins = IntArray(4096)
    var total = 0
    for (box in boxes) for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
        bins[bin(pixels[y * width + x])]++
        total++
    }
    var top = 0
    for (b in 1 until bins.size) if (bins[b] > bins[top]) top = b
    var red = 0L
    var green = 0L
    var blue = 0L
    var members = 0
    for (box in boxes) for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
        val p = pixels[y * width + x]
        if (bin(p) != top) continue
        red += (p shr 16) and 0xFF
        green += (p shr 8) and 0xFF
        blue += p and 0xFF
        members++
    }
    if (members == 0) return null
    val r = red.toFloat() / members
    val g = green.toFloat() / members
    val b = blue.toFloat() / members

    // 0: not the bubble, 1: its colour, 2: reached, 3: somebody else's paragraph.
    val state = ByteArray(width * height)
    for (i in state.indices) {
        val p = pixels[i]
        val off = max(abs(((p shr 16) and 0xFF) - r), max(abs(((p shr 8) and 0xFF) - g), abs((p and 0xFF) - b)))
        if (off <= BG_TOLERANCE) state[i] = 1
    }
    for (rect in blocked) {
        val box = gridBox(rect, width, height)
        for (y in box.y0 until box.y1) state.fill(3, y * width + box.x0, y * width + box.x1)
    }

    val stack = IntArray(width * height)
    var depth = 0
    var seeds = 0
    for (box in boxes) for (y in box.y0 until box.y1) for (p in y * width + box.x0 until y * width + box.x1) {
        if (state[p].toInt() != 1) continue
        state[p] = 2
        stack[depth++] = p
        seeds++
    }
    if (seeds < total * MIN_BG_SHARE) return null

    while (depth > 0) {
        val p = stack[--depth]
        val x = p % width
        if (x > 0 && state[p - 1].toInt() == 1) { state[p - 1] = 2; stack[depth++] = p - 1 }
        if (x < width - 1 && state[p + 1].toInt() == 1) { state[p + 1] = 2; stack[depth++] = p + 1 }
        if (p >= width && state[p - width].toInt() == 1) { state[p - width] = 2; stack[depth++] = p - width }
        if (p + width < state.size && state[p + width].toInt() == 1) { state[p + width] = 2; stack[depth++] = p + width }
    }

    // An edge that is only where the search gave up must not have been reached.
    for (y in 0 until height) {
        if (open.left && state[y * width].toInt() == 2) return null
        if (open.right && state[y * width + width - 1].toInt() == 2) return null
    }
    for (x in 0 until width) {
        if (open.top && state[x].toInt() == 2) return null
        if (open.bottom && state[(height - 1) * width + x].toInt() == 2) return null
    }

    // The line boxes are inside by fiat: glyphs are holes, and the text was there.
    var linesTop = height
    var linesBottom = 0
    var linesLeft = width
    var linesRight = 0
    val thickness = ArrayList<Float>()
    for (box in boxes) {
        for (y in box.y0 until box.y1) state.fill(2, y * width + box.x0, y * width + box.x1)
        linesTop = min(linesTop, box.y0)
        linesBottom = max(linesBottom, box.y1)
        linesLeft = min(linesLeft, box.x0)
        linesRight = max(linesRight, box.x1)
        thickness += min(box.x1 - box.x0, box.y1 - box.y0).toFloat()
    }

    // A comma or a descender crosses the row between two boxes and cuts it in
    // half. On the rows the text stood on, a gap narrower than a line is tall
    // is ink, not outline, unless another paragraph is in it. Only a gap that
    // touches the text's own width, which includes a glyph overhanging its box
    // by a pixel: further out, thin ink is lettering Lens did not read (a
    // trailing "いや…" beside the bubble's main text), and the translation
    // must not be written across it.
    val stroke = median(thickness)
    for (y in linesTop until linesBottom) {
        var reached = -1
        for (x in 0 until width) {
            if (state[y * width + x].toInt() != 2) continue
            if (reached + 1 <= linesRight && x >= linesLeft && x - reached > 1 && x - reached - 1 <= stroke) {
                var clear = true
                for (between in reached + 1 until x) if (state[y * width + between].toInt() == 3) clear = false
                if (clear) state.fill(2, y * width + reached + 1, y * width + x)
            }
            reached = x
        }
    }

    val left = IntArray(height) { -1 }
    val right = IntArray(height) { -1 }
    val anchor = boxes.sortedBy { it.y0 + it.y1 }[boxes.size / 2]
    val row = min(height - 1, (anchor.y0 + anchor.y1) / 2)
    val column = min(width - 1, (anchor.x0 + anchor.x1) / 2)
    var from = column
    var to = column + 1
    while (from > 0 && state[row * width + from - 1].toInt() == 2) from--
    while (to < width && state[row * width + to].toInt() == 2) to++
    left[row] = from
    right[row] = to

    // Gives row y the run that shares the most columns with [lo, hi).
    fun follow(y: Int, lo: Int, hi: Int): Boolean {
        var best = 0
        var x = lo
        while (x > 0 && state[y * width + x].toInt() == 2 && state[y * width + x - 1].toInt() == 2) x--
        while (x < hi) {
            if (state[y * width + x].toInt() != 2) {
                x++
                continue
            }
            var end = x
            while (end < width && state[y * width + end].toInt() == 2) end++
            val shared = min(end, hi) - max(x, lo)
            if (shared > best) {
                best = shared
                left[y] = x
                right[y] = end
            }
            x = end
        }
        return best > 0
    }

    var first = row
    var last = row + 1
    for (y in row - 1 downTo 0) {
        if (!follow(y, left[y + 1], right[y + 1])) break
        first = y
    }
    for (y in row + 1 until height) {
        if (!follow(y, left[y - 1], right[y - 1])) break
        last = y + 1
    }
    // The text stood on every one of its rows, so the area holds them all. A
    // mask that stops short read the ink as the background (lettering so heavy
    // it fills its boxes, or a sign's own colour) and is only the line boxes.
    if (first > linesTop || last < linesBottom) return null

    // The tail is part of the bubble and no part of the text area.
    var widest = 0
    for (y in first until last) widest = max(widest, right[y] - left[y])
    fun narrow(y: Int) = right[y] - left[y] < widest * TAIL_SHARE
    while (first < linesTop && narrow(first)) first++
    while (last > linesBottom && narrow(last - 1)) last--

    var area = 0L
    for (y in first until last) area += right[y] - left[y]
    if (area > maxArea) return null
    val color = ARGB_BLACK or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    return BubbleMask(width, height, first, last, left, right, color)
}

// ---- shape -----------------------------------------------------------------------

/** An area text is set in, asked the only question the wrap has: how wide is the band between two heights. */
internal interface TextShape {
    val top: Float
    val bottom: Float

    /** The `left` to `right` every row of `y0..y1` has in common, or null if there is none. */
    fun span(y0: Float, y1: Float): Pair<Float, Float>?
}

/** A mask as a shape, in image pixels: `x = originX + column * scaleX`. */
internal fun maskShape(mask: BubbleMask, originX: Float, originY: Float, scaleX: Float, scaleY: Float): TextShape =
    object : TextShape {
        override val top = originY + mask.top * scaleY
        override val bottom = originY + mask.bottom * scaleY
        override fun span(y0: Float, y1: Float): Pair<Float, Float>? {
            val from = floor((y0 - originY) / scaleY).toInt()
            val to = ceil((y1 - originY) / scaleY).toInt()
            if (from < mask.top || to > mask.bottom || to <= from) return null
            var lo = Int.MIN_VALUE
            var hi = Int.MAX_VALUE
            for (y in from until to) {
                lo = max(lo, mask.left[y])
                hi = min(hi, mask.right[y])
            }
            return if (hi > lo) (originX + lo * scaleX) to (originX + hi * scaleX) else null
        }
    }

/** The stand-in when the picture shows no bubble: bubbles are round more often than not. */
internal fun ellipseShape(cx: Float, cy: Float, rx: Float, ry: Float): TextShape = object : TextShape {
    override val top = cy - ry
    override val bottom = cy + ry
    override fun span(y0: Float, y1: Float): Pair<Float, Float>? {
        // An ellipse is narrowest at whichever end of the band is further out.
        val reach = max(abs(y0 - cy), abs(y1 - cy)) / ry
        if (!(reach < 1f) || !(rx > 0f)) return null
        val half = rx * sqrt(1f - reach * reach)
        return (cx - half) to (cx + half)
    }
}

/**
 * [inner] made symmetric about the vertical line `x = axis`, and smoothed.
 *
 * Typeset manga is centred on the paragraph's own axis and its line widths
 * change gently. Read straight off an outline, the bands lurch sideways
 * wherever something cuts them (a notch, a tail, a column of lettering Lens
 * did not read), and lines centred on each band zig-zag. So each band is the
 * part of the inner one that is symmetric about the axis, `axis ± min(axis -
 * left, right - axis)`, taken as a running minimum over the band grown by
 * [smoothing] of its height above and below: neighbouring lines cannot jump.
 */
internal fun symmetricShape(inner: TextShape, axis: Float, smoothing: Float = 0.5f): TextShape = object : TextShape {
    override val top = inner.top
    override val bottom = inner.bottom
    override fun span(y0: Float, y1: Float): Pair<Float, Float>? {
        val reach = (y1 - y0) * smoothing
        val span = inner.span(max(top, y0 - reach), min(bottom, y1 + reach)) ?: return null
        val half = min(axis - span.first, span.second - axis)
        return if (half > 0f) (axis - half) to (axis + half) else null
    }
}

// ---- fit -------------------------------------------------------------------------

/** One line of shaped text: its room ([left] to [right], margin taken off) and the top of its em box. */
internal class ShapedLine(val text: String, val left: Float, val right: Float, val y: Float)

internal class ShapedFit(val size: Float, val lines: List<ShapedLine>)

/** Clear space kept between the text and the outline, in ems, on every side. */
internal const val SHAPE_MARGIN = 0.25f

/** The pitch of re-wrapped lines, in font sizes. */
internal const val REFLOW_LINE_SPACING = 1.25f

private class Slot(val from: Int, val to: Int, val left: Float, val right: Float, val y: Float)

/**
 * Wraps token [widths] into [shape] at one size. The block is centred
 * vertically, so where a line's band is depends on how many lines there are:
 * counts are tried from one upwards and the first that takes everything wins.
 *
 * @param anchor the height the block is centred on, as far as the shape lets
 *   it be, or NaN for the middle of the shape. A shape read off a picture can
 *   run on into a tail or a joined bubble; the text belongs where it was.
 */
private fun arrange(
    widths: FloatArray,
    gap: Float,
    shape: TextShape,
    size: Float,
    spacing: Float,
    cap: Float = Float.POSITIVE_INFINITY,
    anchor: Float = Float.NaN,
): List<Slot>? {
    val pitch = size * spacing
    val margin = size * SHAPE_MARGIN
    val middle = (shape.top + shape.bottom) / 2
    val height = shape.bottom - shape.top
    var count = 1
    while (count * pitch <= height && count <= widths.size) {
        val slots = ArrayList<Slot>(count)
        var next = 0
        val blockTop = if (anchor.isNaN()) {
            middle - count * pitch / 2
        } else {
            (anchor - count * pitch / 2).coerceIn(shape.top, shape.bottom - count * pitch)
        }
        for (i in 0 until count) {
            if (next >= widths.size) break
            val y = blockTop + i * pitch + (pitch - size) / 2
            val span = shape.span(y - margin, y + size + margin) ?: break
            val left = span.first + margin
            val right = span.second - margin
            val from = next
            var used = 0f
            while (next < widths.size) {
                val wider = used + (if (next > from) gap else 0f) + widths[next]
                if (wider > min(right - left, cap)) break
                used = wider
                next++
            }
            if (next == from) break
            slots += Slot(from, next, left, right, y)
        }
        if (next >= widths.size) return slots
        count++
    }
    return null
}

/**
 * Evens out a greedy wrap: the same line count with the narrowest line width
 * that does not add a line, so the lines come out of similar length rather
 * than full, full, full and a scrap. Then a last line of one word is given a
 * second from the line above when that still fits (no widow), as long as the
 * paragraph has three words or more.
 */
private fun balance(
    widths: FloatArray,
    gap: Float,
    shape: TextShape,
    size: Float,
    spacing: Float,
    greedy: List<Slot>,
    anchor: Float = Float.NaN,
): List<Slot> {
    val count = greedy.size
    var best = greedy
    if (count >= 2) {
        var lo = widths.max()
        var hi = greedy.maxOf { it.right - it.left }
        repeat(14) {
            if (hi - lo < 0.5f) return@repeat
            val mid = (lo + hi) / 2
            val tried = arrange(widths, gap, shape, size, spacing, cap = mid, anchor = anchor)
            if (tried != null && tried.size == count) {
                best = tried
                hi = mid
            } else {
                lo = mid
            }
        }
    }
    val last = best.lastOrNull() ?: return best
    if (widths.size >= 3 && best.size >= 2 && last.to - last.from == 1) {
        val above = best[best.size - 2]
        if (above.to - above.from >= 2) {
            val moved = widths[above.to - 1] + gap + widths[last.from]
            if (moved <= last.right - last.left) {
                return best.dropLast(2) + listOf(
                    Slot(above.from, above.to - 1, above.left, above.right, above.y),
                    Slot(last.from - 1, last.to, last.left, last.right, last.y),
                )
            }
        }
    }
    return best
}

/** Splits text for wrapping: per character for scripts without spaces, else at spaces. */
internal fun wrapTokens(text: String, perCharacter: Boolean): List<String> {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.isEmpty()) return emptyList()
    return if (perCharacter) codePointStrings(flat) else flat.split(' ')
}

/**
 * The largest size, from [maxSize] down to [minSize] in [step]s, at which
 * [text] fits [shape], with its lines.
 *
 * Not a binary search: in a shape the bands change with the line count and
 * the breaks with the bands, so feasible and infeasible sizes interleave, and
 * a bisection returns whichever its midpoints visit. Every size is tried,
 * cheaply: token widths are taken once at the top and scaled, and only a size
 * that looks feasible is measured for real, which is the layout returned.
 *
 * [measure] gives a string's advance at a size. [anchor] is the height the
 * block is centred on where the shape allows, or NaN for the shape's middle.
 */
internal fun fitShaped(
    measure: (String, Float) -> Float,
    text: String,
    shape: TextShape,
    spacing: Float,
    perCharacter: Boolean,
    maxSize: Float,
    minSize: Float = MIN_FONT_PX,
    step: Float = FONT_STEP_PX,
    anchor: Float = Float.NaN,
): ShapedFit? {
    val tokens = wrapTokens(text, perCharacter)
    if (tokens.isEmpty()) return null
    val joiner = if (perCharacter) "" else " "
    val high = max(minSize, floor(maxSize / step) * step)
    val widths = FloatArray(tokens.size) { measure(tokens[it], high) }
    val gap = if (joiner.isEmpty()) 0f else measure(joiner, high)
    var size = high
    while (size >= minSize) {
        val ratio = size / high
        val scaled = FloatArray(widths.size) { widths[it] * ratio }
        if (arrange(scaled, gap * ratio, shape, size, spacing, anchor = anchor) != null) {
            val real = FloatArray(tokens.size) { measure(tokens[it], size) }
            val realGap = if (joiner.isEmpty()) 0f else measure(joiner, size)
            val greedy = arrange(real, realGap, shape, size, spacing, anchor = anchor)
            val slots = greedy?.let { balance(real, realGap, shape, size, spacing, it, anchor) }
            if (slots != null) {
                val lines = slots.map { ShapedLine(tokens.subList(it.from, it.to).joinToString(joiner), it.left, it.right, it.y) }
                // A line is not exactly the sum of its words; the whole line is what gets painted.
                if (lines.all { measure(it.text, size) <= it.right - it.left + 0.5f }) return ShapedFit(size, lines)
            }
        }
        size -= step
    }
    return null
}

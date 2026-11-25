package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Where a picture has something drawn. Lens reports the text it read and
// nothing else: not the icon beside a label, the rule under a heading, the
// outline of a bubble, nor lettering it missed. Text that leaves its own box
// has to keep clear of all of that, so the picture itself is asked.
//
// Arithmetic on numbers handed in, like the bubble mask: tested without a
// graphics backend.

/** Neighbouring cells this far apart in any channel have an edge between them. */
internal const val DETAIL_GAP: Int = 32

/** The most cells a picture is read at for its detail map; a larger picture is averaged down first. */
internal const val DETAIL_CELLS: Float = 1_200_000f

/**
 * A map of a picture's edges that answers "is anything drawn here?" for any
 * rectangle in constant time.
 */
internal class DetailMap(
    /** A cell's size in image pixels. */
    private val cellWidth: Float,
    private val cellHeight: Float,
    private val columns: Int,
    private val rows: Int,
    /** How many cells with detail lie above and to the left of each cell corner. */
    private val sum: IntArray,
) {
    /** How many cells with detail [rect], in image pixels, touches. */
    fun count(rect: Rect): Int {
        val x0 = floor(rect.left / cellWidth).toInt().coerceIn(0, columns)
        val x1 = ceil(rect.right / cellWidth).toInt().coerceIn(0, columns)
        val y0 = floor(rect.top / cellHeight).toInt().coerceIn(0, rows)
        val y1 = ceil(rect.bottom / cellHeight).toInt().coerceIn(0, rows)
        if (x1 <= x0 || y1 <= y0) return 0
        val stride = columns + 1
        return sum[y1 * stride + x1] - sum[y0 * stride + x1] - sum[y1 * stride + x0] + sum[y0 * stride + x0]
    }

    /** Whether nothing is drawn in [rect]. */
    fun plain(rect: Rect): Boolean = count(rect) == 0
}

private fun channelGap(a: Int, b: Int): Int = max(
    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
    max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
)

/**
 * The detail map of a picture given as [columns] x [rows] opaque ARGB [cells],
 * each [cellWidth] x [cellHeight] image pixels.
 *
 * A cell has detail when an edge runs through it: a neighbour [DETAIL_GAP] or
 * more away in some channel. Grain and compression make such pairs too, one
 * here and one there, so a cell only counts with two more beside it: a drawn
 * edge is a line of them, noise is not.
 */
internal fun detailMap(cells: IntArray, columns: Int, rows: Int, cellWidth: Float, cellHeight: Float): DetailMap {
    require(columns > 0 && rows > 0 && cells.size >= columns * rows) { "bad cell grid ${columns}x$rows/${cells.size}" }
    val edge = BooleanArray(columns * rows)
    for (y in 0 until rows) {
        for (x in 0 until columns) {
            val i = y * columns + x
            if (x + 1 < columns && channelGap(cells[i], cells[i + 1]) >= DETAIL_GAP) {
                edge[i] = true
                edge[i + 1] = true
            }
            if (y + 1 < rows && channelGap(cells[i], cells[i + columns]) >= DETAIL_GAP) {
                edge[i] = true
                edge[i + columns] = true
            }
        }
    }
    val stride = columns + 1
    val sum = IntArray(stride * (rows + 1))
    for (y in 0 until rows) {
        var row = 0
        for (x in 0 until columns) {
            val i = y * columns + x
            if (edge[i]) {
                var around = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= rows) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx >= 0 && nx < columns && edge[ny * columns + nx]) around++
                    }
                }
                if (around >= 3) row++
            }
            sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row
        }
    }
    return DetailMap(cellWidth, cellHeight, columns, rows, sum)
}

/**
 * [image]'s pixels in `sx, sy, sw x sh`, averaged down to `gw x gh` opaque
 * cells (anything translucent over white: transparent would read as black,
 * and black is the one colour an outline is). Read in bands of rows, so a big
 * picture never needs a full copy. Null if it cannot be read.
 */
internal fun readCells(image: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, gw: Int, gh: Int): IntArray? = try {
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
        image.readPixels(buffer, sx, sy + y0, sw, rows)
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
 * The detail map of [image], which must be readable ([forSoftwareCanvas]),
 * or null when it cannot be read after all. A picture of more than
 * [DETAIL_CELLS] pixels is averaged down to that many cells first.
 */
internal fun detailOf(image: ImageBitmap): DetailMap? {
    if (image.width < 1 || image.height < 1) return null
    val shrink = min(1f, sqrt(DETAIL_CELLS / (image.width.toFloat() * image.height)))
    val columns = max(1, (image.width * shrink).roundToInt())
    val rows = max(1, (image.height * shrink).roundToInt())
    val cells = readCells(image, 0, 0, image.width, image.height, columns, rows) ?: return null
    return detailMap(cells, columns, rows, image.width.toFloat() / columns, image.height.toFloat() / rows)
}

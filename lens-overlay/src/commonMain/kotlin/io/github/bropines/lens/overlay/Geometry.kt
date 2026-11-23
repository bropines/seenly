package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A [Box] in image pixels: a `width` x `height` rectangle centred on
 * ([centerX], [centerY]) and turned [rotation] radians clockwise about it.
 */
internal data class OrientedBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
) {
    val center: Offset get() = Offset(centerX, centerY)
    val rotationDegrees: Float get() = (rotation * 180.0 / PI).toFloat()
    val area: Float get() = width * height

    /** The rectangle before rotation; draw into it inside `rotate(rotationDegrees, center)`. */
    val frame: Rect get() = Rect(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)

    /** The same box with each side moved outwards by [dx] and [dy] pixels. */
    fun inflate(dx: Float, dy: Float): OrientedBox =
        copy(width = max(0f, width + 2 * dx), height = max(0f, height + 2 * dy))

    /** Whether [point] lies inside, or within [slop] pixels of, the rotated box. */
    fun contains(point: Offset, slop: Float = 0f): Boolean {
        val local = toLocal(point)
        return abs(local.x) <= width / 2 + slop && abs(local.y) <= height / 2 + slop
    }

    /** [point] relative to the centre, with the rotation undone. */
    fun toLocal(point: Offset): Offset {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val c = cos(rotation)
        val s = sin(rotation)
        // The inverse of a clockwise turn (y points down), i.e. the transpose.
        return Offset(dx * c + dy * s, -dx * s + dy * c)
    }

    /** The corners in image space: top-left, top-right, bottom-right, bottom-left. */
    fun corners(): List<Offset> {
        val c = cos(rotation)
        val s = sin(rotation)
        val hw = width / 2
        val hh = height / 2
        return listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) ->
            Offset(centerX + x * c - y * s, centerY + x * s + y * c)
        }
    }

    /** The axis-aligned rectangle that encloses the rotated box. */
    fun bounds(): Rect {
        val c = abs(cos(rotation))
        val s = abs(sin(rotation))
        val halfX = (width * c + height * s) / 2
        val halfY = (width * s + height * c) / 2
        return Rect(centerX - halfX, centerY - halfY, centerX + halfX, centerY + halfY)
    }
}

/** This normalised box in the pixels of an image of the given size. */
internal fun Box.toPixels(imageWidth: Float, imageHeight: Float): OrientedBox =
    OrientedBox(centerX * imageWidth, centerY * imageHeight, width * imageWidth, height * imageHeight, rotation)

/**
 * Where a line's background patch is drawn.
 *
 * Both paddings are fractions of the line box's **height** (not of the side
 * they pad), and each is the total for its axis: half goes on either side, so
 * the patch stays centred on the line. This is Chromium's
 * `getBackgroundImageDataStyle()`; its division by the aspect ratio only turns
 * the horizontal padding into a fraction of the image width, which pixels do
 * not need.
 */
internal fun patchBox(line: OrientedBox, horizontalPadding: Float, verticalPadding: Float): OrientedBox =
    line.copy(
        width = line.width + horizontalPadding * line.height,
        height = line.height + verticalPadding * line.height,
    )

/** The convex hull of [points], by Andrew's monotone chain. */
internal fun convexHull(points: List<Offset>): List<Offset> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size < 3) return sorted

    fun half(sequence: List<Offset>): List<Offset> {
        val chain = ArrayList<Offset>()
        for (point in sequence) {
            while (chain.size >= 2) {
                val o = chain[chain.size - 2]
                val a = chain[chain.size - 1]
                if ((a.x - o.x) * (point.y - o.y) - (a.y - o.y) * (point.x - o.x) > 0) break
                chain.removeAt(chain.size - 1)
            }
            chain.add(point)
        }
        chain.removeAt(chain.size - 1)
        return chain
    }
    return half(sorted) + half(sorted.asReversed())
}

/** Never let two paragraphs share an edge; a pixel of daylight reads as one. */
private const val NEIGHBOUR_GAP = 2f

/**
 * How much room a reflowed paragraph has: [own] grown by [growth] about its
 * centre, stopped by the image's edges and by any neighbour that sits beside
 * it. A neighbour that already overlaps [own] is ignored, so the result never
 * comes out smaller than [own] itself.
 */
internal fun roomFor(own: Rect, others: List<Rect>, growth: Float, imageWidth: Float, imageHeight: Float): Rect {
    val growX = own.width * (growth - 1f) / 2
    val growY = own.height * (growth - 1f) / 2
    var left = max(0f, own.left - growX)
    var right = min(imageWidth, own.right + growX)
    var top = max(0f, own.top - growY)
    var bottom = min(imageHeight, own.bottom + growY)

    for (other in others) {
        if (other.bottom > own.top && other.top < own.bottom) {
            if (other.right <= own.left) left = max(left, other.right + NEIGHBOUR_GAP)
            if (other.left >= own.right) right = min(right, other.left - NEIGHBOUR_GAP)
        }
        if (other.right > own.left && other.left < own.right) {
            if (other.bottom <= own.top) top = max(top, other.bottom + NEIGHBOUR_GAP)
            if (other.top >= own.bottom) bottom = min(bottom, other.top - NEIGHBOUR_GAP)
        }
    }
    return Rect(min(left, own.left), min(top, own.top), max(right, own.right), max(bottom, own.bottom))
}

/** The boxes that make a paragraph tappable: its own box and every line's. */
private fun Paragraph.hitBoxes(imageSize: Size): List<OrientedBox> = buildList {
    box?.let { add(it.toPixels(imageSize.width, imageSize.height)) }
    lines.forEach { line -> line.box?.let { add(it.toPixels(imageSize.width, imageSize.height)) } }
}

/**
 * The index of the paragraph at [position], or null when there is none.
 *
 * [position] is in the pixels of an image of [imageSize] (any copy of the
 * scanned image: boxes are normalised). Rotated boxes are tested as rotated.
 * Where paragraphs overlap, the smallest one wins, so a caption inside a larger
 * block stays reachable. When nothing is hit exactly, the nearest paragraph
 * within [slop] pixels is taken, which makes small text tappable with a finger.
 */
public fun ScanResult.paragraphAt(position: Offset, imageSize: Size, slop: Float = 0f): Int? {
    var best: Int? = null
    var bestArea = Float.MAX_VALUE
    for (pass in 0..1) {
        val reach = if (pass == 0) 0f else slop
        paragraphs.forEachIndexed { index, paragraph ->
            val boxes = paragraph.hitBoxes(imageSize)
            if (boxes.any { it.contains(position, reach) }) {
                val area = boxes.maxOf { it.area }
                if (area < bestArea) {
                    bestArea = area
                    best = index
                }
            }
        }
        if (best != null || slop <= 0f) break
    }
    return best
}

package io.github.bropines.lens.screen

import kotlin.math.abs

/** A rectangle in frame pixels. Plain Kotlin, so everything that decides when to translate can be tested off a device. */
internal data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    /** The part of this inside [other], or null when they do not meet. */
    fun intersect(other: Region): Region? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) Region(l, t, r, b) else null
    }
}

/**
 * A frame reduced to a coarse grid of brightness samples: enough to tell
 * "the picture moved" from "the picture is the same", for almost nothing.
 *
 * [xs] and [ys] are the frame pixels the samples were read at, so a caller can
 * ask what else (its own overlay) sits on each of them. [skipped] marks the
 * samples that were not read at all because something of ours (the bubble) is
 * drawn there: they never count, whichever of two signatures left them out.
 */
internal class Signature(
    val roi: Region,
    val cells: ByteArray,
    val xs: IntArray,
    val ys: IntArray,
    val skipped: BooleanArray? = null,
) {
    /** Signatures of different regions are not comparable. */
    fun comparable(other: Signature): Boolean = roi == other.roi && cells.size == other.cells.size

    /** Whether sample [i] was read in both this and [other]. */
    fun usable(i: Int, other: Signature): Boolean =
        (skipped == null || !skipped[i]) && (other.skipped == null || !other.skipped[i])

    /** Whether sample [i] is visibly brighter or darker than [other]'s. */
    fun differs(i: Int, other: Signature): Boolean =
        abs((cells[i].toInt() and 0xFF) - (other.cells[i].toInt() and 0xFF)) > TOLERANCE

    /**
     * How many samples differ visibly from [other]'s, leaving out the ones
     * [ignore] marks. Signatures that are not [comparable] count as entirely
     * different.
     */
    fun diff(other: Signature, ignore: BooleanArray? = null): Int {
        if (!comparable(other)) return Int.MAX_VALUE
        var count = 0
        for (i in cells.indices) {
            if (ignore != null && ignore[i]) continue
            if (usable(i, other) && differs(i, other)) count++
        }
        return count
    }

    companion object {
        const val COLS = 40
        const val ROWS = 72
        /** Brightness steps (of 255) a sample may drift before it counts as changed. */
        const val TOLERANCE = 10
    }
}

/** One captured frame as the translator's logic sees it. */
internal class Frame(
    val signature: Signature,
    /**
     * `SystemClock.uptimeMillis()` no later than the moment the frame was
     * taken. Screenshots arrive hundreds of milliseconds late, so whether a
     * frame shows our own drawing is decided by this, never by when it arrived.
     */
    val takenAt: Long,
    /** Grows with every frame of one source: tells a new frame from one already looked at. */
    val number: Long,
)

/** The brightness grid [Signature] keeps, read from any pixel source. */
internal inline fun sampleSignature(
    frameWidth: Int,
    frameHeight: Int,
    roi: Region?,
    skip: Region?,
    luma: (x: Int, y: Int) -> Int,
): Signature {
    val frame = Region(0, 0, frameWidth, frameHeight)
    val region = roi?.intersect(frame) ?: frame
    val cols = Signature.COLS
    val rows = Signature.ROWS
    val xs = IntArray(cols) { region.left + ((it + 0.5f) * region.width / cols).toInt() }
    val ys = IntArray(rows) { region.top + ((it + 0.5f) * region.height / rows).toInt() }
    val cells = ByteArray(cols * rows)
    var skipped: BooleanArray? = null
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if (skip != null && skip.contains(xs[c], ys[r])) {
                val marks = skipped ?: BooleanArray(cols * rows).also { skipped = it }
                marks[r * cols + c] = true
                continue
            }
            cells[r * cols + c] = luma(xs[c], ys[r]).toByte()
        }
    }
    return Signature(region, cells, xs, ys, skipped)
}

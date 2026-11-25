package io.github.bropines.lens.screen

import kotlin.math.max

/*
 * Keeping a translation on screen when the page under it has only moved.
 *
 * After a scroll, most of what is on screen was there a moment ago, a few
 * hundred pixels higher or lower, already translated. So that it does not sit
 * untranslated for the second or two the new request takes, the pieces of the
 * old translation that still have their own text under them are put back at
 * once, where that text is now. The new translation replaces them when it
 * comes. The same goes for a page where only one corner changed: everything
 * else keeps its translation.
 *
 * "Still has its own text under it" is decided exactly, row by row: a piece is
 * put back only if every row of pixels under it (and a margin around it) is
 * found unchanged in the new frame. Plain Kotlin, tested off a device.
 */

/**
 * One number per pixel row of a clean frame: equal rows have equal numbers. A
 * row of one flat colour is marked, because it looks like every other row of
 * that colour and so says nothing about where the page went.
 *
 * Only the middle of each row is read: the far edges are where scrollbars
 * fade in and out, and the columns from [skipFrom] until [skipTo] are where
 * the bubble is painted out.
 */
internal class RowPrint(
    val width: Int,
    val skipFrom: Int,
    val skipTo: Int,
    val hashes: IntArray,
    val flat: BooleanArray,
) {
    val height: Int get() = hashes.size

    /** Prints of differently sized frames, or read at different columns, say nothing about each other. */
    fun comparable(other: RowPrint): Boolean =
        width == other.width && height == other.height && skipFrom == other.skipFrom && skipTo == other.skipTo

    @Volatile
    private var byHash: Map<Int, IntArray>? = null

    /** The rows that have something on them, by their number. Numbers that many rows share are left out as ambiguous. */
    fun index(): Map<Int, IntArray> = byHash ?: run {
        val rows = HashMap<Int, IntArray>()
        val crowded = HashSet<Int>()
        for (y in hashes.indices) {
            if (flat[y]) continue
            val hash = hashes[y]
            if (hash in crowded) continue
            val known = rows[hash]
            when {
                known == null -> rows[hash] = intArrayOf(y)
                known.size >= MAX_REPEATS -> {
                    rows.remove(hash)
                    crowded += hash
                }
                else -> rows[hash] = known + y
            }
        }
        rows.also { byHash = it }
    }

    companion object {
        private const val STEP = 4
        private const val MAX_REPEATS = 4

        /** The print of [argb] (a frame's pixels, row by row). */
        fun of(argb: IntArray, width: Int, height: Int, skipFrom: Int = 0, skipTo: Int = 0): RowPrint {
            val from = width * 5 / 100
            val to = width - from
            val hashes = IntArray(height)
            val flat = BooleanArray(height)
            for (y in 0 until height) {
                val row = y * width
                var hash = 17
                var first = 0
                var seen = false
                var same = true
                var x = from
                while (x < to) {
                    if (x < skipFrom || x >= skipTo) {
                        val pixel = argb[row + x] and 0xFFFFFF
                        hash = hash * 31 + pixel
                        if (!seen) {
                            first = pixel
                            seen = true
                        } else if (pixel != first) {
                            same = false
                        }
                    }
                    x += STEP
                }
                hashes[y] = hash
                flat[y] = same
            }
            return RowPrint(width, skipFrom, skipTo, hashes, flat)
        }
    }
}

/** A piece of a translation: the rows from [top] until [bottom] of the frame it was made from. */
internal class Island(val top: Int, val bottom: Int)

internal object Sticky {
    /** A translation remembered: the print of the clean frame it was made from, and its pieces. */
    class Seen(val print: RowPrint, val islands: List<Island>)

    /** Piece [island] of `seen[seen]` belongs [dy] rows further down in the new frame. */
    class Placement(val seen: Int, val island: Int, val dy: Int)

    /** Fewer matching rows than this do not make a shift believable. */
    private const val MIN_VOTES = 32
    private const val MAX_SHIFTS = 4
    /** A piece needs this many rows with something on them to be recognised by. */
    private const val MIN_DETAIL = 3

    /**
     * Which pieces of the remembered translations ([seen], newest first) still
     * have their own text under them in the frame [now] is the print of, and
     * where. A piece is placed at most once, and never over rows a newer
     * translation already claimed.
     *
     * [clearance] is how many rows above and below a piece must have moved
     * with it. A piece next to rows that are new (the page scrolled them into
     * view) is likely the visible half of a paragraph whose other half has just
     * appeared, untranslated: such a piece is left out.
     */
    fun place(now: RowPrint, seen: List<Seen>, clearance: Int): List<Placement> {
        val placed = ArrayList<Placement>()
        val claimed = ArrayList<IntRange>()
        for ((k, old) in seen.withIndex()) {
            if (old.islands.isEmpty() || !now.comparable(old.print)) continue
            val shifts = shifts(now, old.print)
            if (shifts.isEmpty()) continue
            for ((j, island) in old.islands.withIndex()) {
                val dy = shifts.firstOrNull { fits(now, old.print, island, it, clearance) } ?: continue
                val rows = (island.top + dy) until (island.bottom + dy)
                if (claimed.any { it.first <= rows.last && rows.first <= it.last }) continue
                claimed += rows
                placed += Placement(k, j, dy)
            }
        }
        return placed
    }

    /**
     * How far the picture of [old] may have moved to become [now]: every row
     * with something on it votes for the distance to each row it equals.
     * Several answers can be right at once: the app bar stayed (0) while the
     * list under it went up. Best first.
     */
    internal fun shifts(now: RowPrint, old: RowPrint): IntArray {
        val height = now.height
        val votes = IntArray(2 * height + 1)
        val index = old.index()
        for (y in 0 until height) {
            if (now.flat[y]) continue
            val rows = index[now.hashes[y]] ?: continue
            for (r in rows) votes[y - r + height]++
        }
        val best = ArrayList<Int>()
        for (i in votes.indices) if (votes[i] >= MIN_VOTES) best += i
        best.sortByDescending { votes[it] }
        return IntArray(minOf(best.size, MAX_SHIFTS)) { best[it] - height }
    }

    private fun fits(now: RowPrint, old: RowPrint, island: Island, dy: Int, clearance: Int): Boolean {
        if (island.top < 0 || island.bottom > old.height || island.top >= island.bottom) return false
        // Partly off the picture now: half a translated line is worse than none.
        if (island.top + dy < 0 || island.bottom + dy > now.height) return false
        var detail = 0
        for (r in island.top until island.bottom) {
            if (old.hashes[r] != now.hashes[r + dy]) return false
            if (!old.flat[r]) detail++
        }
        if (detail < MIN_DETAIL) return false
        for (k in 1..clearance) {
            if (!movedAlong(now, old, island.top - k, dy) || !movedAlong(now, old, island.bottom - 1 + k, dy)) return false
        }
        return true
    }

    /** Whether row [r] beside a piece went where the piece went. */
    private fun movedAlong(now: RowPrint, old: RowPrint, r: Int, dy: Int): Boolean {
        val n = r + dy
        // Off the new picture: nothing there to be wrong about.
        if (n < 0 || n >= now.height) return true
        // On it, but off the old one: this row is new, and may be the rest of the paragraph.
        if (r < 0 || r >= old.height) return false
        return old.hashes[r] == now.hashes[n]
    }
}

/** The rows [top] until [bottom] of a rendered translation and the columns [left] until [right] it uses there. */
internal class Band(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Groups the rows a translation draws on into bands, so each can be kept (and
 * put back after a scroll) on its own, and so the empty stretches between them
 * cost no memory. Row y is drawn on from [minX]`[y]` to [maxX]`[y]` inclusive,
 * or not at all when `maxX[y] < minX[y]`. Fewer than [gap] empty rows do not
 * split a band: the lines of one paragraph stay together. When that still
 * makes more than [limit] bands, wider gaps are bridged too.
 */
internal fun bands(minX: IntArray, maxX: IntArray, gap: Int, limit: Int): List<Band> {
    var bridge = max(1, gap)
    while (true) {
        val out = ArrayList<Band>()
        var top = -1
        var last = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in minX.indices) {
            if (maxX[y] < minX[y]) continue
            if (top >= 0 && y - last > bridge) {
                out += Band(left, top, right + 1, last + 1)
                top = -1
                left = Int.MAX_VALUE
                right = -1
            }
            if (top < 0) top = y
            last = y
            if (minX[y] < left) left = minX[y]
            if (maxX[y] > right) right = maxX[y]
        }
        if (top >= 0) out += Band(left, top, right + 1, last + 1)
        if (out.size <= max(1, limit)) return out
        bridge *= 2
    }
}

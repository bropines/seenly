package io.github.bropines.lens.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Putting pieces of an old translation back where their text went: [RowPrint], [Sticky], [bands]. */
class StickyTest {
    private val width = 240
    private val height = 600
    private val header = 50

    /** A document row: forty rows of "text" (every row its own pattern), then ten of plain paper. */
    private fun documentRow(row: Int, x: Int): Int =
        if (row % 50 >= 40) PAPER else PAPER xor (((row * 73 + x / 4 * 31) * -1640531535) ushr 12 and 0x00FFFFFF)

    /** An app: a bar at the top that stays, and under it a document scrolled to [scroll]. [edit] repaints rows of it. */
    private fun frame(scroll: Int, edit: (row: Int) -> Boolean = { false }, scrollbar: Int = 0): IntArray {
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                argb[y * width + x] = OPAQUE or when {
                    // A scrollbar thumb by the right edge, wherever it happens to be.
                    scrollbar != 0 && x >= width - 6 && y in scrollbar until scrollbar + 80 -> 0x808080
                    y < header -> 0x203040 xor ((y * 7 + x / 4) * 2654435 and 0x0F0F0F)
                    edit(y) -> 0x00AA00 xor ((y * 13 + x / 4 * 7) * 40503 and 0xFFFF)
                    else -> documentRow(scroll + y, x)
                }
            }
        }
        return argb
    }

    private fun print(argb: IntArray, skipFrom: Int = 0, skipTo: Int = 0) = RowPrint.of(argb, width, height, skipFrom, skipTo)

    private fun placed(now: RowPrint, old: RowPrint, vararg islands: Island, clearance: Int = 20): Map<Int, Int> =
        Sticky.place(now, listOf(Sticky.Seen(old, islands.toList())), clearance).associate { it.island to it.dy }

    // ---- prints ----

    @Test
    fun `a print tells rows apart, and knows the plain ones`() {
        val print = print(frame(scroll = 0))
        assertEquals(height, print.height)
        // Document rows 40..49 are paper; on screen they sit under the fifty rows of the bar.
        assertTrue((0 until height).filter { print.flat[it] }.all { it >= header && it % 50 >= 40 })
        assertEquals(110, (0 until height).count { print.flat[it] })
        assertEquals(1, (60..85).map { print.hashes[it] }.groupBy { it }.values.maxOf { it.size })
        assertEquals(print.hashes[95], print.hashes[145])
    }

    @Test
    fun `the far edges and the bubble's columns are not part of a print`() {
        val clean = frame(scroll = 0)
        // A scrollbar thumb fades in over the last few columns.
        assertTrue(print(clean).hashes.contentEquals(print(frame(scroll = 0, scrollbar = 200)).hashes))
        // Something drawn where the bubble is: seen, unless those columns are skipped.
        val marked = clean.copyOf()
        for (y in 300 until 360) for (x in 180 until 220) marked[y * width + x] = OPAQUE or 0xFF00FF
        assertFalse(print(clean).hashes.contentEquals(print(marked).hashes))
        assertTrue(print(clean, 176, 224).hashes.contentEquals(print(marked, 176, 224).hashes))
        assertFalse(print(clean).comparable(print(clean, 176, 224)))
    }

    // ---- where the page went ----

    @Test
    fun `a scroll is found as the list having moved while the bar stayed`() {
        val before = print(frame(scroll = 0))
        val after = print(frame(scroll = 120))
        val shifts = Sticky.shifts(after, before)
        assertEquals(listOf(-120, 0), shifts.toList())
        assertEquals(listOf(120, 0), Sticky.shifts(before, after).toList())
        // Of a page with nothing else in common, only the bar is found, where it was.
        assertEquals(listOf(0), Sticky.shifts(print(frame(scroll = 5_000)), before).toList())
    }

    @Test
    fun `after a scroll the pieces whose text is still in view go where it went`() {
        val before = print(frame(scroll = 0))
        val after = print(frame(scroll = 120))
        val placed = placed(
            after, before,
            Island(10, 30),     // 0: in the bar, which did not move
            Island(60, 90),     // 1: scrolled out at the top
            Island(210, 240),   // 2: a paragraph still in view
            Island(410, 490),   // 3: two paragraphs in one piece, still in view
            Island(560, 590),   // 4: by the bottom edge: what is under it now was not on screen before
        )
        assertEquals(mapOf(0 to 0, 2 to -120, 3 to -120), placed)
    }

    @Test
    fun `scrolling back puts them back`() {
        val before = print(frame(scroll = 120))
        val after = print(frame(scroll = 0))
        // Rows 60..90 were just under the bar: what is above them now was hidden by it then.
        assertEquals(mapOf(1 to 120), placed(after, before, Island(60, 90), Island(210, 240)))
    }

    @Test
    fun `when one part of the page changes the rest keeps its translation`() {
        val before = print(frame(scroll = 0))
        val after = print(frame(scroll = 0, edit = { it in 300..339 }))
        val placed = placed(
            after, before,
            Island(10, 30),     // 0: the bar
            Island(210, 240),   // 1: well above the change
            Island(290, 310),   // 2: in it
            Island(345, 360),   // 3: right under it: too close to say its paragraph is the same
            Island(410, 440),   // 4: well below
        )
        assertEquals(mapOf(0 to 0, 1 to 0, 4 to 0), placed)
    }

    @Test
    fun `a piece over plain paper has nothing to be recognised by`() {
        val before = print(frame(scroll = 0))
        assertEquals(emptyMap<Int, Int>(), placed(print(frame(scroll = 0)), before, Island(91, 99), clearance = 0))
        // Two rows of text are not enough either; four are.
        assertEquals(emptyMap<Int, Int>(), placed(print(frame(scroll = 0)), before, Island(88, 99), clearance = 0))
        assertEquals(mapOf(0 to 0), placed(print(frame(scroll = 0)), before, Island(86, 99), clearance = 0))
    }

    @Test
    fun `prints that cannot be compared place nothing`() {
        val before = print(frame(scroll = 0), 200, 240)
        val after = print(frame(scroll = 0))
        assertEquals(emptyMap<Int, Int>(), placed(after, before, Island(210, 240)))
    }

    @Test
    fun `a piece is placed once, the newest translation of it winning`() {
        val newest = Sticky.Seen(print(frame(scroll = 100)), listOf(Island(210, 240)))
        val older = Sticky.Seen(print(frame(scroll = 0)), listOf(Island(310, 340), Island(410, 440)))
        val now = print(frame(scroll = 100))
        val placed = Sticky.place(now, listOf(newest, older), clearance = 10)
        // The older view's first piece is the same rows of the document as the newest's only one.
        assertEquals(listOf(Triple(0, 0, 0), Triple(1, 1, -100)), placed.map { Triple(it.seen, it.island, it.dy) })
    }

    // ---- bands ----

    @Test
    fun `rows drawn on are grouped into bands, paragraphs staying whole`() {
        val minX = IntArray(100) { Int.MAX_VALUE }
        val maxX = IntArray(100) { -1 }
        fun line(rows: IntRange, from: Int, to: Int) = rows.forEach {
            minX[it] = from
            maxX[it] = to
        }
        line(10..19, 20, 180)   // a paragraph of two lines,
        line(23..32, 20, 120)   // three rows apart
        line(60..69, 40, 90)    // and another, well below
        val bands = bands(minX, maxX, gap = 6, limit = 10).map { listOf(it.left, it.top, it.right, it.bottom) }
        assertEquals(listOf(listOf(20, 10, 181, 33), listOf(40, 60, 91, 70)), bands)
        // With a gap of two rows, every line is a band.
        assertEquals(3, bands(minX, maxX, gap = 2, limit = 10).size)
        // Too many bands: the gaps between them are bridged until they are few enough.
        assertEquals(1, bands(minX, maxX, gap = 2, limit = 1).size)
        assertEquals(0, bands(IntArray(5) { Int.MAX_VALUE }, IntArray(5) { -1 }, gap = 2, limit = 4).size)
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
        const val PAPER = 0xF4F0E8
    }
}

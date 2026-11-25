package io.github.bropines.lens.app.ui.viewer

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayBoundTest {
    private val max = 3072
    private val limit = 16_384

    /** The decoded size of a w x h picture fitted into the square [displayBound] gives. */
    private fun decoded(w: Int, h: Int, sideLimit: Int = limit): Pair<Int, Int> {
        val bound = displayBound(w, h, max, sideLimit)
        val scale = minOf(1.0, bound.toDouble() / maxOf(w, h))
        return (w * scale).toInt() to (h * scale).toInt()
    }

    @Test
    fun ordinaryPicturesAreBoundedOnTheirLongerSideAsBefore() {
        assertEquals(max, displayBound(2400, 3600, max, limit))
        assertEquals(max, displayBound(4000, 4000, max, limit))
        assertEquals(max, displayBound(8160, 6120, max, limit))
        // Small enough already: nothing to scale.
        assertEquals(max, displayBound(1080, 2400, max, limit))
    }

    @Test
    fun aLongStripKeepsItsWidthInsteadOfBecomingASliver() {
        // A webtoon strip: bounding its length to 3072 would leave it 205 px wide.
        val (w, h) = decoded(800, 12_000)
        assertTrue(w >= 640, "the strip is $w px wide")
        // ...and it costs no more memory than a 2:3 page does.
        assertTrue(w.toLong() * h <= 3072L * 2048 * 1.02, "the strip is ${w}x$h")

        // A long screenshot that fits the budget is not scaled at all.
        assertEquals(1080 to 5000, decoded(1080, 5000))
    }

    @Test
    fun noSideGoesPastWhatThePlatformCanDraw() {
        val (_, h) = decoded(600, 60_000)
        assertTrue(h <= limit)
        // The old renderer's texture limit bounds a strip harder.
        val (_, old) = decoded(800, 12_000, sideLimit = 4096)
        assertTrue(old in 4090..4096, "the strip is $old px long")
    }

    @Test
    fun stripsAreLongPicturesThatWouldBeNarrowFittedWhole() {
        val phone = IntSize(1080, 2400)
        assertTrue(isStrip(800, 12_000, phone))
        assertTrue(isStrip(1080, 7000, phone))
        // A page, a phone screenshot on a phone: fitted whole.
        assertFalse(isStrip(1600, 2400, phone))
        assertFalse(isStrip(1080, 2400, phone))
        // On a wide window a manga page is still a page, not a strip.
        assertFalse(isStrip(1600, 2400, IntSize(1100, 800)))
        assertTrue(isStrip(800, 12_000, IntSize(1100, 800)))
        assertFalse(isStrip(0, 0, phone))
        assertFalse(isStrip(800, 12_000, IntSize.Zero))
    }
}

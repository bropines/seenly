package io.github.bropines.lens.app.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ImageRotationTest {
    // 3 wide, 2 high:
    //   1 2 3
    //   4 5 6
    private val pixels = intArrayOf(1, 2, 3, 4, 5, 6)

    @Test
    fun quarterTurnClockwiseMakesTheTopRowTheRightColumn() {
        // 2 wide, 3 high:
        //   4 1
        //   5 2
        //   6 3
        assertContentEquals(intArrayOf(4, 1, 5, 2, 6, 3), rotateArgb(pixels, 3, 2, 1))
    }

    @Test
    fun halfTurnReversesThePixels() {
        assertContentEquals(intArrayOf(6, 5, 4, 3, 2, 1), rotateArgb(pixels, 3, 2, 2))
    }

    @Test
    fun threeQuartersIsAQuarterCounterclockwise() {
        //   3 6
        //   2 5
        //   1 4
        assertContentEquals(intArrayOf(3, 6, 2, 5, 1, 4), rotateArgb(pixels, 3, 2, 3))
    }

    @Test
    fun fourQuartersAreNoTurn() {
        var p = pixels
        var w = 3
        var h = 2
        repeat(4) {
            p = rotateArgb(p, w, h, 1)
            w = h.also { h = w }
        }
        assertContentEquals(pixels, p)
        assertContentEquals(pixels, rotateArgb(pixels, 3, 2, 4))
    }

    @Test
    fun turnsNormalise() {
        assertEquals(0, quarterTurns(0))
        assertEquals(1, quarterTurns(5))
        assertEquals(3, quarterTurns(-1))
    }
}

package io.github.bropines.lens.app.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/** Any number of clockwise quarter turns as 0..3. */
fun quarterTurns(turns: Int): Int = ((turns % 4) + 4) % 4

/**
 * [argb] (row by row, [width] x [height]) turned clockwise by [turns] quarter
 * turns. An odd turn swaps the sides: the result is [height] wide.
 */
fun rotateArgb(argb: IntArray, width: Int, height: Int, turns: Int): IntArray {
    require(argb.size == width * height) { "bad pixels ${width}x$height/${argb.size}" }
    val q = quarterTurns(turns)
    if (q == 0) return argb.copyOf()
    val out = IntArray(argb.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val target = when (q) {
                // Clockwise: the top row becomes the right column.
                1 -> x * height + (height - 1 - y)
                2 -> (height - 1 - y) * width + (width - 1 - x)
                else -> (width - 1 - x) * height + y
            }
            out[target] = argb[row + x]
        }
    }
    return out
}

/** This bitmap turned clockwise by [turns] quarter turns; the same bitmap at 0. */
fun ImageBitmap.rotated(turns: Int): ImageBitmap {
    val q = quarterTurns(turns)
    if (q == 0) return this
    val w = if (q % 2 == 1) height else width
    val h = if (q % 2 == 1) width else height
    val out = ImageBitmap(w, h)
    Canvas(out).apply {
        when (q) {
            1 -> translate(height.toFloat(), 0f)
            2 -> translate(width.toFloat(), height.toFloat())
            else -> translate(0f, width.toFloat())
        }
        rotate(90f * q)
        drawImage(this@rotated, Offset.Zero, Paint())
    }
    return out
}

/**
 * Encoded image [bytes] turned clockwise by [turns] quarter turns, for upload:
 * PNG when the picture has transparency, else JPEG at quality 95. Lens's own
 * upload preparation scales and recompresses it afterwards as usual. The
 * input itself at 0 turns.
 */
expect fun rotateEncodedImage(bytes: ByteArray, turns: Int): ByteArray

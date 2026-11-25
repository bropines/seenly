package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * The platform a render test runs on: its fonts, its codecs and its files.
 * Skia on the JVM, Android's own graphics (through Robolectric) on the host.
 */
interface RenderBackend {
    /** Names the folder the backend's pictures and reports are kept in. */
    val name: String

    /** Where text gets its fonts on this platform. */
    val fonts: FontFamily.Resolver

    /** Whether the platform can draw at all: a bare machine may have neither Skia's libraries nor a font. */
    val available: Boolean

    fun encodePng(image: ImageBitmap): ByteArray

    fun <T> blocking(block: suspend () -> T): T

    /** A file from `src/commonTest/resources/fixtures`, or null when there is none. */
    fun readFixture(name: String): ByteArray?

    /** Keeps [bytes] as `build/[directory]/<backend>/[file]` for a human, or a later run, to look at. */
    fun save(directory: String, file: String, bytes: ByteArray)
}

/** A new [width] x [height] bitmap with [block] drawn into it, one unit to the pixel. */
internal fun drawBitmap(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val out = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(width.toFloat(), height.toFloat()), block)
    return out
}

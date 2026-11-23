package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.bropines.lens.protocol.ScanResult

/**
 * Paints the translation onto a copy of [image] at the image's full resolution,
 * for "export translation as image". It draws exactly what [TranslatedImage]
 * shows in [OverlayMode.Translated], with the same code.
 *
 * This measures text and fills a full-size bitmap: call it off the main thread.
 *
 * @param decoded the decoded patches of [result], from [rememberOverlayState]
 *   or [decodeOverlay]. Its patches are matched to [result]'s lines by position.
 * @param minFontPx the readable-size floor in image pixels. There is no screen
 *   here to measure [OverlayStyle.minFontSp] against, so by default it is taken
 *   as if the image were shown 360 dp wide, a phone held upright.
 * @return a new bitmap of [image]'s size; [image] is left untouched.
 */
public fun renderTranslatedBitmap(
    image: ImageBitmap,
    result: ScanResult,
    style: OverlayStyle,
    decoded: OverlayState,
    minFontPx: Float = style.minFontSp * image.width / 360f,
): ImageBitmap {
    val source = image.forSoftwareCanvas()
    val scene = buildOverlayScene(decoded, result, style, source, minFontPx.coerceAtLeast(0f))
    val output = ImageBitmap(source.width, source.height)
    val size = Size(source.width.toFloat(), source.height.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), size) {
        drawImage(source)
        drawOverlayScene(scene)
    }
    return output
}

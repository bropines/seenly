package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.github.bropines.lens.protocol.ScanResult

// The overlay is laid out once, in the image's own pixels, into a flat list of
// drawing operations. The composable replays that list under its fit-and-zoom
// transform, and the exporter replays it at full resolution: both therefore
// draw exactly the same thing, and zooming never re-measures any text.
//
// The layout itself is in SceneBuilder.kt.

/** Something that removes source text. Drawn before any translated text. */
internal sealed interface EraseOp

/** The server's inpainted background, stretched over [box]. */
internal class PatchOp(val box: OrientedBox, val image: ImageBitmap) : EraseOp

/** A flat colour over [box], for a line that has no usable patch. */
internal class RectFillOp(val box: OrientedBox, val color: Color) : EraseOp

/**
 * A flat colour over a paragraph's hull, grown by [padding] pixels with round
 * corners. Nothing is painted inside [keepOut]: the padding reaches past the
 * text, and what lies there may be a paragraph that was not translated. Nor
 * is anything painted outside [within], when it is given: the inside of the
 * bubble the paragraph stands in, whose outline the padding must not eat.
 */
internal class HullFillOp(
    val path: Path,
    val color: Color,
    val padding: Float,
    val keepOut: List<Rect>,
    val within: Path? = null,
) : EraseOp


/**
 * One piece of laid-out text.
 *
 * It is drawn inside `rotate(rotationDegrees, pivot)`, which is the frame of
 * the box it belongs to, with its top-left corner at [topLeft]. With
 * [quarterTurn] it is turned a further 90 degrees clockwise about [topLeft],
 * for text that lies on its side in a vertical column.
 *
 * Colours and the outline's stroke are baked into the layouts: changing a
 * paragraph's paint between draws makes Skia lay it out again, every frame.
 */
internal class TextOp(
    val paragraph: Int,
    val pivot: Offset,
    val rotationDegrees: Float,
    val topLeft: Offset,
    val quarterTurn: Boolean,
    val fill: TextLayoutResult,
    val outline: TextLayoutResult?,
    val fontSize: Float,
)

/** Everything [OverlayMode.Translated] draws over an image, in that image's pixels. */
internal class OverlayScene(
    val source: OverlayState,
    val imageWidth: Int,
    val imageHeight: Int,
    val erases: List<EraseOp>,
    val texts: List<TextOp>,
)

/**
 * Lays [result] out over an image of [image]'s size.
 *
 * Text is measured here, which is the slow part: call it off the main thread.
 *
 * @param state the decoded patches; normally decoded from [result] itself.
 * @param minFontPx the readable-size floor in image pixels, or 0 for none.
 */
internal fun buildOverlayScene(
    state: OverlayState,
    result: ScanResult,
    style: OverlayStyle,
    image: ImageBitmap,
    minFontPx: Float,
    /** Told, block by block, what each was taken for and what size it was given: for a test to print. */
    trace: ((String) -> Unit)? = null,
): OverlayScene = SceneBuilder(state, result, style, image, minFontPx, trace).build()

// ---- drawing ---------------------------------------------------------------

/** Draws [scene]. The scope's coordinates must be the image's pixels. */
internal fun DrawScope.drawOverlayScene(scene: OverlayScene) {
    for (op in scene.erases) {
        when (op) {
            is PatchOp -> rotate(op.box.rotationDegrees, op.box.center) {
                val frame = op.box.frame
                withTransform({
                    translate(frame.left, frame.top)
                    scale(frame.width / op.image.width, frame.height / op.image.height, Offset.Zero)
                }) {
                    // Bilinear: the patch is small and heavily compressed, and
                    // sharper filters ring around the erased glyphs.
                    drawImage(op.image, filterQuality = FilterQuality.Low)
                }
            }

            is RectFillOp -> rotate(op.box.rotationDegrees, op.box.center) {
                drawRect(op.color, op.box.frame.topLeft, op.box.frame.size)
            }

            is HullFillOp -> drawIntoCanvas { canvas ->
                canvas.save()
                for (rect in op.keepOut) canvas.clipRect(rect, ClipOp.Difference)
                op.within?.let { canvas.clipPath(it) }
                drawPath(op.path, op.color)
                if (op.padding > 0f) {
                    // A round-jointed stroke grows the shape by the padding in
                    // every direction and rounds its corners in one step.
                    drawPath(op.path, op.color, style = Stroke(op.padding * 2, join = StrokeJoin.Round))
                }
                canvas.restore()
            }
        }
    }
    // All text after all erasing: text that leaves its box must not be wiped
    // out by the next paragraph's patch.
    for (op in scene.texts) {
        rotate(op.rotationDegrees, op.pivot) {
            if (op.quarterTurn) rotate(90f, op.topLeft) { drawTextOp(op) } else drawTextOp(op)
        }
    }
}

private fun DrawScope.drawTextOp(op: TextOp) {
    op.outline?.let { drawText(it, topLeft = op.topLeft) }
    drawText(op.fill, topLeft = op.topLeft)
}

package io.github.bropines.lens.overlay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import io.github.bropines.lens.protocol.ScanResult
import kotlin.math.min

// OverlayMode.TextBoxes, the "text" mode: the picture dimmed around the text,
// every line softly tinted, and a selection the user can drag.

/** How much of the picture's light the dimming takes away outside the text. */
private const val DIM_ALPHA = 0.32f
private const val LINE_TINT_ALPHA = 0.22f
private const val SELECTION_ALPHA = 0.5f

private fun DrawScope.roundedBox(box: OrientedBox, radius: Float, color: Color, alpha: Float, blendMode: BlendMode = BlendMode.SrcOver) {
    val frame = box.frame
    val corner = CornerRadius(min(radius, min(frame.width, frame.height) / 2))
    rotate(box.rotationDegrees, box.center) {
        drawRoundRect(color, frame.topLeft, frame.size, corner, alpha = alpha, blendMode = blendMode)
    }
}

/**
 * Draws text mode over the image. The scope's coordinates must be the image's
 * pixels.
 *
 * @param dp one screen dp in image pixels at the current zoom, so that
 *   paddings, corners and handles keep their size on screen.
 * @param alpha fades the whole layer in and out; the selection is not faded.
 * @param selection the selected part of each line, from [WordLayout.lineSelections].
 */
internal fun DrawScope.drawTextMode(
    result: ScanResult,
    imageWidth: Float,
    imageHeight: Float,
    color: Color,
    dp: Float,
    alpha: Float = 1f,
    selection: List<LineSelection> = emptyList(),
) {
    val paragraphs = result.paragraphs.map { paragraph ->
        paragraph.box?.toPixels(imageWidth, imageHeight) to paragraph.lines.mapNotNull { it.box?.toPixels(imageWidth, imageHeight) }
    }

    // The dimming, with the text cut out of it. A layer rather than a path
    // with holes: paragraphs overlap, and holes that overlap fill back in.
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, imageWidth, imageHeight), Paint())
        drawRect(Color.Black, Offset.Zero, Size(imageWidth, imageHeight), alpha = DIM_ALPHA * alpha)
        for ((paragraph, lines) in paragraphs) {
            val areas = if (paragraph != null) listOf(paragraph) else lines
            for (area in areas) roundedBox(area.inflate(4 * dp, 4 * dp), 8 * dp, Color.Black, 1f, BlendMode.Clear)
        }
        canvas.restore()
    }

    for ((_, lines) in paragraphs) {
        for (line in lines) roundedBox(line.inflate(dp, dp), 4 * dp, color, LINE_TINT_ALPHA * alpha)
    }

    if (selection.isEmpty()) return
    for (line in selection) roundedBox(line.box.inflate(dp, dp), 4 * dp, color, SELECTION_ALPHA)
    val radius = HANDLE_RADIUS_DP * dp
    drawHandle(handlePlacement(selection.first(), SelectionEnd.Start, radius), radius, color)
    drawHandle(handlePlacement(selection.last(), SelectionEnd.End, radius), radius, color)
}

/** A teardrop: a disc with one square corner, and the corner points at the text. */
private fun DrawScope.drawHandle(handle: HandlePlacement, radius: Float, color: Color) {
    // In the line's own frame the disc sits one radius along the diagonal
    // from the anchor, and the square corner fills the gap between them.
    rotate(handle.rotationDegrees, handle.anchor) {
        val center = handle.anchor + handle.away * radius
        drawCircle(color, radius, center)
        drawRect(color, Offset(min(center.x, handle.anchor.x), min(center.y, handle.anchor.y)), Size(radius, radius))
    }
}

/** The round part of a handle, in dp on screen. */
internal const val HANDLE_RADIUS_DP: Float = 9f

/** How far from a handle's centre a touch still grabs it, in dp. Fingers are not styluses. */
private const val HANDLE_GRAB_DP = 24f

/** How far from a word a long press may land and still select it, in dp. */
private const val WORD_SLOP_DP = 16f

/**
 * The selection gestures of text mode.
 *
 * - Touching a handle and dragging moves that end of the selection.
 * - A long press on a word selects it, and dragging on without lifting
 *   extends the selection word by word; the pressed word stays selected.
 *
 * Everything else is left untouched for the tap and zoom detectors: a quick
 * tap, a pinch, a pan. Once a selection drag has begun every movement is
 * consumed, so neither the zoom nor a pager or scrolling parent can take it.
 *
 * @param onLongPress called when a long press selects a word, for haptics.
 * @param onStep called whenever a drag changes the selection, for haptics.
 */
internal suspend fun PointerInputScope.detectSelectionGestures(
    state: TextSelectionState,
    onLongPress: () -> Unit,
    onStep: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val layout = state.layout ?: return@awaitEachGesture
        val scale = state.viewScale
        if (scale <= 0f) return@awaitEachGesture
        val dp = density / scale

        fun PointerInputChange.track(fixed: Int, adjust: Offset) {
            val word = layout.nearestWord(state.viewToImage(position + adjust))
            if (word != null && state.dragTo(fixed, word)) onStep()
            consume()
        }

        // 1. A handle: no waiting, it is grabbed the moment it is touched.
        val grabbed = state.selection?.let { range ->
            val lines = layout.lineSelections(range)
            if (lines.isEmpty()) return@let null
            val radius = HANDLE_RADIUS_DP * dp
            val candidates = listOf(
                Triple(handlePlacement(lines.last(), SelectionEnd.End, radius), range.start, SelectionEnd.End),
                Triple(handlePlacement(lines.first(), SelectionEnd.Start, radius), range.endInclusive, SelectionEnd.Start),
            )
            candidates
                .map { it to (state.imageToView(it.first.center) - down.position).getDistance() }
                .filter { it.second <= HANDLE_GRAB_DP * density }
                .minByOrNull { it.second }
                ?.first
        }
        if (grabbed != null) {
            val (handle, fixed) = grabbed
            // The finger holds the handle, which hangs beside the text; the
            // selection should follow the text edge it stands for, not the finger.
            val adjust = state.imageToView(handle.grip) - down.position
            down.consume()
            state.isDragging = true
            try {
                drag(down.id) { it.track(fixed, adjust) }
                currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
            } finally {
                state.isDragging = false
            }
            return@awaitEachGesture
        }

        // 2. A long press on a word, then a drag that extends from it.
        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val word = layout.nearestWord(state.viewToImage(press.position), WORD_SLOP_DP * dp) ?: return@awaitEachGesture
        state.select(WordRange(word, word))
        onLongPress()
        state.isDragging = true
        try {
            drag(press.id) { it.track(word, Offset.Zero) }
            // Without this the release would also count as a tap, and clear or replace the selection.
            currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
        } finally {
            state.isDragging = false
        }
    }
}

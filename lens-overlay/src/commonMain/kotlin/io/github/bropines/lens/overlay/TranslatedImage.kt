package io.github.bropines.lens.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows [image] fitted to the available space with Lens's [result] over it.
 *
 * In [OverlayMode.Translated] the source text is erased and the translation
 * drawn in its place, as [style] says; [OverlayMode.TextBoxes] is the text
 * mode, with the picture dimmed around the text, every line tinted and, given
 * a [selection], text the user can select; [OverlayMode.Original] shows the
 * bare image. The overlay shares the image's transform, so it stays put
 * while the user pinches to zoom, drags to pan or double-taps.
 *
 * [result]'s patches are decoded off the main thread; until that is done the
 * image is shown without translations. To share the decoded state with
 * [renderTranslatedBitmap], decode it yourself with [rememberOverlayState] and
 * use the overload that takes an [OverlayState].
 *
 * @param onParagraphTap called with an index into [ScanResult.paragraphs] when
 *   a paragraph is tapped. Null leaves single taps alone.
 * @param onBackgroundTap called for a single tap that hits no paragraph (or
 *   any single tap when [onParagraphTap] is null), e.g. to toggle the app's chrome.
 * @param zoomState the zoom and pan; hoist it to read or drive them.
 * @param selection turns on text selection in [OverlayMode.TextBoxes]: a tap
 *   selects the paragraph under it (and one on empty space clears the selection
 *   and calls [onBackgroundTap]), a long press selects a word and dragging on
 *   extends it, and two handles move the ends. [onParagraphTap] is not called
 *   in that mode then. Null leaves text mode a plain highlight.
 */
@Composable
public fun TranslatedImage(
    image: ImageBitmap,
    result: ScanResult?,
    mode: OverlayMode,
    style: OverlayStyle = OverlayStyle(),
    modifier: Modifier = Modifier,
    onParagraphTap: ((Int) -> Unit)? = null,
    onBackgroundTap: (() -> Unit)? = null,
    zoomState: ZoomState = rememberZoomState(),
    selection: TextSelectionState? = null,
) {
    TranslatedImageImpl(
        image, result, rememberOverlayState(result), mode, style, modifier, onParagraphTap, onBackgroundTap, zoomState, selection,
    )
}

/**
 * [TranslatedImage] for a result that is already decoded.
 *
 * Use this to decode once and share the [OverlayState] with
 * [renderTranslatedBitmap]. While [state] is null the bare image is shown.
 */
@Composable
public fun TranslatedImage(
    image: ImageBitmap,
    state: OverlayState?,
    mode: OverlayMode,
    style: OverlayStyle = OverlayStyle(),
    modifier: Modifier = Modifier,
    onParagraphTap: ((Int) -> Unit)? = null,
    onBackgroundTap: (() -> Unit)? = null,
    zoomState: ZoomState = rememberZoomState(),
    selection: TextSelectionState? = null,
) {
    TranslatedImageImpl(image, state?.result, state, mode, style, modifier, onParagraphTap, onBackgroundTap, zoomState, selection)
}

/** Where an image of [imageWidth] x [imageHeight] sits when fitted, centred, into [viewport]. */
internal fun fitRect(imageWidth: Int, imageHeight: Int, viewport: Size): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || viewport.width <= 0f || viewport.height <= 0f) return Rect.Zero
    val scale = min(viewport.width / imageWidth, viewport.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (viewport.width - w) / 2
    val top = (viewport.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/** The view's own size: all the room it is given, or the image's shape where room is unbounded. */
internal fun measureImage(imageWidth: Int, imageHeight: Int, constraints: Constraints): IntSize {
    val aspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f
    val width: Int
    val height: Int
    when {
        constraints.hasBoundedWidth && constraints.hasBoundedHeight -> {
            width = constraints.maxWidth
            height = constraints.maxHeight
        }
        constraints.hasBoundedWidth -> {
            width = constraints.maxWidth
            height = (width / aspect).roundToInt()
        }
        constraints.hasBoundedHeight -> {
            height = constraints.maxHeight
            width = (height * aspect).roundToInt()
        }
        else -> {
            width = imageWidth
            height = imageHeight
        }
    }
    return IntSize(
        width.coerceIn(constraints.minWidth, constraints.maxWidth),
        height.coerceIn(constraints.minHeight, constraints.maxHeight),
    )
}

/** How long a change of [OverlayMode] takes to fade. */
private const val MODE_FADE_MS = 120

/** How close, on screen, a tap has to come to a paragraph to count. */
private val TapSlop = 12.dp

@Composable
private fun TranslatedImageImpl(
    image: ImageBitmap,
    result: ScanResult?,
    state: OverlayState?,
    mode: OverlayMode,
    style: OverlayStyle,
    modifier: Modifier,
    onParagraphTap: ((Int) -> Unit)?,
    onBackgroundTap: (() -> Unit)?,
    zoomState: ZoomState,
    selection: TextSelectionState?,
) {
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(viewportSize, image) { fitRect(image.width, image.height, viewportSize.toSize()) }
    val fitScale = if (image.width > 0) fit.width / image.width else 0f
    SideEffect { zoomState.updateBounds(viewportSize.toSize(), fit) }

    // The readable-size floor is in sp on screen; the scene is laid out in
    // image pixels. Quantised, so a resize by a pixel does not lay out again.
    val minFontPx = if (style.minFontSp > 0f && fitScale > 0f) {
        val exact = with(density) { style.minFontSp.sp.toPx() } / fitScale
        (exact * 2).roundToInt() / 2f
    } else {
        0f
    }
    val sceneReady = style.minFontSp <= 0f || fitScale > 0f
    val latestScene by produceState<OverlayScene?>(null, state, style, image, minFontPx, sceneReady) {
        value = if (state != null && sceneReady) {
            withContext(Dispatchers.Default) { buildOverlayScene(state, state.result, style, image, minFontPx) }
        } else {
            null
        }
    }
    // While a new scene is being laid out the previous one stays up, unless it
    // belongs to another result or another image: then nothing beats wrong.
    val scene = latestScene?.takeIf { it.source === state && it.imageWidth == image.width && it.imageHeight == image.height }

    // Short and without a spring's tail: switching modes should feel immediate.
    val translatedAlpha by animateFloatAsState(if (mode == OverlayMode.Translated) 1f else 0f, tween(MODE_FADE_MS))
    val boxesAlpha by animateFloatAsState(if (mode == OverlayMode.TextBoxes) 1f else 0f, tween(MODE_FADE_MS))

    // Selection lives in text mode only. The word boxes are worked out once
    // per result and image size; the state is told what is on screen so it
    // can hit-test and report where the selection is.
    val selecting = selection != null && mode == OverlayMode.TextBoxes
    val wordLayout = remember(result, image.width, image.height, selecting) {
        if (selecting && result != null) WordLayout(result, image.width.toFloat(), image.height.toFloat()) else null
    }
    if (selection != null) {
        SideEffect { selection.bind(wordLayout, image.width.toFloat(), fit, zoomState) }
        LaunchedEffect(selection, selecting) { if (!selecting) selection.clear() }
    }
    val haptics = LocalHapticFeedback.current

    val scope = rememberCoroutineScope()
    val currentTap by rememberUpdatedState(onParagraphTap)
    val currentBackgroundTap by rememberUpdatedState(onBackgroundTap)
    val currentResult by rememberUpdatedState(result)
    val currentFit by rememberUpdatedState(fit)
    val currentImage by rememberUpdatedState(image)
    val currentSelection by rememberUpdatedState(if (selecting) selection else null)

    Layout(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(zoomState) { detectZoomGestures(zoomState) }
            .pointerInput(zoomState) {
                detectTapGestures(
                    onDoubleTap = { position -> scope.launch { zoomState.toggle(position) } },
                    onTap = { position ->
                        val tap = currentTap
                        val scan = currentResult
                        val rect = currentFit
                        var hit: Int? = null
                        if (scan != null && !rect.isEmpty) {
                            val scale = rect.width / currentImage.width
                            val inImage = (zoomState.toUnzoomed(position) - rect.topLeft) / scale
                            val imageSize = Size(currentImage.width.toFloat(), currentImage.height.toFloat())
                            val slop = TapSlop.toPx() / (scale * zoomState.scale)
                            hit = scan.paragraphAt(inImage, imageSize, slop)
                        }
                        val selectionState = currentSelection
                        when {
                            selectionState != null ->
                                if (hit == null || !selectionState.selectParagraph(hit)) {
                                    selectionState.clear()
                                    currentBackgroundTap?.invoke()
                                }
                            hit != null && tap != null -> tap(hit)
                            else -> currentBackgroundTap?.invoke()
                        }
                    },
                )
            }
            // After the tap detector, so it sees each event first: a release
            // that ends a selection drag is consumed before it can count as a tap.
            .then(
                if (selecting && selection != null) {
                    Modifier.pointerInput(selection) {
                        detectSelectionGestures(
                            state = selection,
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onStep = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                if (fit.isEmpty) return@drawBehind
                val zoom = zoomState.scale
                val offset = zoomState.offset
                withTransform({
                    translate(offset.x + fit.left * zoom, offset.y + fit.top * zoom)
                    scale(zoom * fitScale, zoom * fitScale, Offset.Zero)
                }) {
                    drawImage(image)
                    val bounds = Rect(0f, 0f, image.width.toFloat(), image.height.toFloat())
                    if (scene != null) withAlpha(translatedAlpha, bounds) { drawOverlayScene(scene) }
                    if (result != null && boxesAlpha > 0.004f) {
                        // One dp in image pixels: sizes stay constant on screen, whatever the zoom.
                        val dp = 1.dp.toPx() / (zoom * fitScale)
                        // Read here, in the draw phase: a new selection is on screen the next frame,
                        // without recomposing and without any animation in between.
                        val selected = selection?.selection?.let { wordLayout?.lineSelections(it) }.orEmpty()
                        drawTextMode(result, bounds.width, bounds.height, style.highlightColor, dp, boxesAlpha, selected)
                    }
                }
            },
        content = {},
    ) { _, constraints ->
        val size = measureImage(image.width, image.height, constraints)
        layout(size.width, size.height) {}
    }
}

/** Draws [block] at [alpha], through an offscreen layer only while it is actually translucent. */
private inline fun DrawScope.withAlpha(alpha: Float, bounds: Rect, block: DrawScope.() -> Unit) {
    when {
        alpha <= 0.004f -> Unit
        alpha >= 0.996f -> block()
        else -> drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
            block()
            canvas.restore()
        }
    }
}

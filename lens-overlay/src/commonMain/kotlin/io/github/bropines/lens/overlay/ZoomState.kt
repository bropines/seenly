package io.github.bropines.lens.overlay

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * The zoom and pan of a [TranslatedImage].
 *
 * A point of the unzoomed view lands at `offset + scale * point`. The state is
 * hoistable: keep it to read the zoom, to reset it when the image changes, or
 * to drive it from buttons.
 *
 * @param minScale the smallest scale; 1 is the image fitted to the view.
 * @param maxScale the largest scale.
 */
@Stable
public class ZoomState(
    public val minScale: Float = 1f,
    public val maxScale: Float = 8f,
) {
    init {
        require(minScale > 0f && maxScale >= minScale) { "Need 0 < minScale <= maxScale, got $minScale..$maxScale" }
    }

    /** The current scale; 1 is the image fitted to the view. */
    public var scale: Float by mutableFloatStateOf(1f)
        private set

    /** Where the unzoomed view's top-left corner sits, in view pixels. */
    public var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Whether the image is magnified, i.e. a one-finger drag pans it. */
    public val isZoomed: Boolean get() = scale > 1.01f

    private var viewport: Size = Size.Zero
    private var content: Rect = Rect.Zero

    /** Tells the state the view's size and where the fitted image sits in it. */
    internal fun updateBounds(viewport: Size, content: Rect) {
        if (viewport == this.viewport && content == this.content) return
        this.viewport = viewport
        this.content = content
        offset = clampOffset(offset, scale, viewport, content)
    }

    /** Where the view point [position] falls in the unzoomed view. */
    public fun toUnzoomed(position: Offset): Offset = (position - offset) / scale

    /**
     * Applies one step of a gesture: scale by [zoom] about [centroid], then
     * move by [pan]. Both scale and offset are kept within bounds.
     */
    public fun transformBy(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val factor = newScale / scale
        val moved = centroid - (centroid - offset) * factor + pan
        scale = newScale
        offset = clampOffset(moved, newScale, viewport, content)
    }

    /** Jumps to [scale], keeping the view point [focus] where it is. */
    public fun snapTo(scale: Float, focus: Offset = viewport.center()) {
        transformBy(focus, Offset.Zero, scale.coerceIn(minScale, maxScale) / this.scale)
    }

    /** Animates to [scale], keeping the view point [focus] where it is. */
    public suspend fun animateTo(
        scale: Float,
        focus: Offset = viewport.center(),
        animationSpec: AnimationSpec<Float> = tween(ZOOM_ANIMATION_MS),
    ) {
        val startScale = this.scale
        val startOffset = offset
        val endScale = scale.coerceIn(minScale, maxScale)
        val endOffset = clampOffset(focus - (focus - startOffset) * (endScale / startScale), endScale, viewport, content)
        animate(0f, 1f, animationSpec = animationSpec) { t, _ ->
            val s = startScale + (endScale - startScale) * t
            this.scale = s
            offset = clampOffset(startOffset + (endOffset - startOffset) * t, s, viewport, content)
        }
    }

    /** Animates back to the fitted image. */
    public suspend fun reset() {
        animateTo(minScale.coerceAtLeast(1f).coerceAtMost(maxScale))
    }

    /** Double-tap behaviour: zoom in on [focus], or back out when already zoomed. */
    internal suspend fun toggle(focus: Offset) {
        if (isZoomed) reset() else animateTo(DOUBLE_TAP_SCALE, focus)
    }

    private companion object {
        const val DOUBLE_TAP_SCALE = 2.5f

        /** Short, and a tween rather than a spring: no tail to wait out after a double tap. */
        const val ZOOM_ANIMATION_MS = 150
    }
}

private fun Size.center(): Offset = Offset(width / 2, height / 2)

/** Creates and remembers a [ZoomState]. */
@Composable
public fun rememberZoomState(minScale: Float = 1f, maxScale: Float = 8f): ZoomState =
    remember(minScale, maxScale) { ZoomState(minScale, maxScale) }

/**
 * Keeps the zoomed image on screen.
 *
 * [content] is where the image sits in the unzoomed [viewport]. Along each
 * axis, an image smaller than the view is centred, and a larger one may be
 * panned only until its edge meets the view's edge.
 */
internal fun clampOffset(offset: Offset, scale: Float, viewport: Size, content: Rect): Offset {
    if (viewport.width <= 0f || viewport.height <= 0f || content.isEmpty) return offset

    fun axis(value: Float, view: Float, start: Float, extent: Float): Float {
        val scaled = extent * scale
        // "+ 0f" turns a negative zero into a plain one, so equal offsets compare equal.
        return 0f + if (scaled <= view) {
            (view - scaled) / 2 - start * scale
        } else {
            value.coerceIn(view - (start + extent) * scale, -start * scale)
        }
    }
    return Offset(
        axis(offset.x, viewport.width, content.left, content.width),
        axis(offset.y, viewport.height, content.top, content.height),
    )
}

/**
 * Pinch to zoom, drag to pan, mouse wheel to zoom.
 *
 * A one-finger drag is left alone while the image is not zoomed, so a pager or
 * a scrolling parent still gets it.
 */
internal suspend fun PointerInputScope.detectZoomGestures(state: ZoomState) {
    awaitEachGesture {
        val first = awaitPointerEvent()
        if (first.type == PointerEventType.Scroll) {
            val change = first.changes.firstOrNull() ?: return@awaitEachGesture
            val delta = change.scrollDelta.y
            if (delta != 0f && !change.isConsumed) {
                val before = state.scale
                state.transformBy(change.position, Offset.Zero, if (delta < 0f) WHEEL_STEP else 1f / WHEEL_STEP)
                if (state.scale != before) change.consume()
            }
            return@awaitEachGesture
        }
        if (first.type != PointerEventType.Press) return@awaitEachGesture

        val slop = viewConfiguration.touchSlop
        var pastSlop = false
        var zoomSoFar = 1f
        var panSoFar = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (!pastSlop) {
                    zoomSoFar *= zoom
                    panSoFar += pan
                    val fingers = event.changes.count { it.pressed }
                    val zoomMotion = abs(1 - zoomSoFar) * event.calculateCentroidSize(useCurrent = false)
                    val panning = panSoFar.getDistance() > slop && (fingers > 1 || state.isZoomed)
                    if (zoomMotion > slop || panning) pastSlop = true
                }
                if (pastSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoom != 1f || pan != Offset.Zero) state.transformBy(centroid, pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })
    }
}

private const val WHEEL_STEP = 1.2f

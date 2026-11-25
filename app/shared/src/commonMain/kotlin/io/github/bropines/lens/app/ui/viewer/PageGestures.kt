package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * What a finger may do with the page in front besides what the picture
 * itself takes (a tap, a pinch, a selection): pull it down to put it away,
 * and hold it for the quick actions.
 */
class PageGestures(
    /** Whether a pull down may start now (not while zoomed in, searching, or mid-flight). */
    val canDismiss: () -> Boolean,
    /** A pull began where the finger first touched, in the page's pixels. */
    val onDismissStart: (at: Offset) -> Unit,
    /** How far the finger has moved since. */
    val onDismissDrag: (total: Offset) -> Unit,
    /** The finger was lifted, moving at [velocity] pixels per second. */
    val onDismissEnd: (velocity: Offset) -> Unit,
    /** A second finger came down or the system took the gesture: put the page back. */
    val onDismissCancel: () -> Unit,
    val canHold: () -> Boolean,
    /** The page was held (or right-clicked) at this point. */
    val onHold: (at: Offset) -> Unit,
)

/** How much more down than sideways a movement must be to count as a pull: sideways is the pager's. */
private const val DOWNWARD_BIAS = 1.4f

/**
 * A one-finger pull downwards.
 *
 * It claims the gesture only once the finger has moved past the touch slop
 * and mostly down; until then nothing is consumed, so a tap, a sideways swipe
 * (the pager's) and a pinch go where they belong. A movement someone else
 * has consumed (the zoomed picture panning, a selection being dragged) is not
 * a pull either.
 */
internal suspend fun PointerInputScope.detectDismissDrag(gestures: PageGestures) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!gestures.canDismiss()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var pulling = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                // Two fingers are a pinch.
                if (pulling) gestures.onDismissCancel()
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (pulling) {
                    // The lift counts too: a finger that stopped before letting go threw nothing.
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    gestures.onDismissEnd(tracker.calculateVelocity().let { Offset(it.x, it.y) })
                }
                return@awaitEachGesture
            }
            if (!pulling) {
                if (change.isConsumed) return@awaitEachGesture
                total += change.positionChange()
                if (total.getDistance() > slop) {
                    if (total.y <= 0f || total.y < abs(total.x) * DOWNWARD_BIAS || !gestures.canDismiss()) return@awaitEachGesture
                    pulling = true
                    change.consume()
                    gestures.onDismissStart(down.position)
                    gestures.onDismissDrag(total)
                }
            } else {
                total += change.positionChange()
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                gestures.onDismissDrag(total)
            }
        }
        if (pulling) gestures.onDismissCancel()
    }
}

/**
 * A press held still for the long-press time, or a secondary mouse click.
 * Once it fires, the rest of the gesture is swallowed: the release must not
 * also count as a tap on the picture beneath.
 */
internal suspend fun PointerInputScope.detectHold(gestures: PageGestures) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!gestures.canHold()) return@awaitEachGesture
        if (currentEvent.type == PointerEventType.Press && currentEvent.buttons.isSecondaryPressed) {
            gestures.onHold(down.position)
        } else {
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!gestures.canHold()) return@awaitEachGesture
            gestures.onHold(press.position)
        }
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

package io.github.bropines.lens.screen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import io.github.bropines.lens.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The whole display in pixels, bars and cutouts included: what the capture sees. */
internal fun WindowManager.screenSize(): Point =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else {
        @Suppress("DEPRECATION")
        Point().also { defaultDisplay.getRealSize(it) }
    }

/** The system bars and cutouts as (left, top, right, bottom): where the bubble should not rest. */
internal fun WindowManager.barInsets(density: Float): Rect {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insets = maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(insets.left, insets.top, insets.right, insets.bottom)
    }
    return Rect(0, (24 * density).toInt(), 0, (48 * density).toInt())
}

/**
 * Layout params for a window placed in raw screen coordinates: it ignores the
 * bars and cutouts, so (x, y) here is (x, y) in a captured frame.
 */
internal fun overlayParams(width: Int, height: Int, flags: Int = 0): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        width,
        height,
        ScreenOverlayHost.windowType,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // No system enter/exit animation: these windows come and go many times a minute.
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

/** A piece of a translation: [bitmap] goes on screen pixel for pixel, its corner at ([left], [top]). */
internal class Piece(val bitmap: Bitmap, val left: Int, val top: Int) {
    val right: Int get() = left + bitmap.width
    val bottom: Int get() = top + bitmap.height

    /** Whether this piece paints the screen pixel ([x], [y]). */
    fun paints(x: Int, y: Int): Boolean =
        x >= left && y >= top && x < right && y < bottom && !bitmap.isRecycled && bitmap.getPixel(x - left, y - top) ushr 24 != 0
}

/**
 * The layer the translation is drawn on, plus the area's frame.
 *
 * It has two personalities. Pass-through (live and area modes): not touchable,
 * so the user keeps scrolling the app underneath. Android 12+ lets touches
 * through another app's window only if that window is at most 80% opaque, so
 * in this personality the whole layer is drawn at that opacity. Modal ("this
 * screen"): fully opaque where it paints, and a touch anywhere dismisses it.
 */
@SuppressLint("ViewConstructor")
internal class LayerView(
    context: Context,
    private val wm: WindowManager,
    private val onDismiss: () -> Unit,
) : View(context) {
    private val params = overlayParams(
        1, 1,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    )
    private val bitmapPaint = Paint()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_DP * resources.displayMetrics.density
        color = 0xFF9E9BFF.toInt()
    }
    private val location = IntArray(2)
    private var pieces: List<Piece> = emptyList()
    /** The rectangle that holds every piece. */
    private val extent = Rect()
    private val screen = Rect()
    private var attached = false
    private var passThrough = true

    init {
        // Nothing to show yet, and an empty layer must never be in the way of a touch.
        visibility = GONE
    }

    /** The area's outer rectangle in screen pixels, or null when there is no area. */
    var area: Rect? = null
        set(value) {
            field = value?.let { Rect(it) }
            refresh()
        }

    /** How dark, in percent, the screen goes under a modal ("this screen") translation. */
    var scrim: Int = 0

    private val scrimPaint = Paint()

    /** Dims the area's frame while the area is paused. */
    var areaDimmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val hasContent: Boolean get() = pieces.isNotEmpty()

    /** When our own drawing last left the screen: frames older than this may still show it. */
    var clearedAt: Long = 0L
        private set

    fun attach(size: Point) {
        screen.set(0, 0, size.x, size.y)
        params.x = 0
        params.y = 0
        params.width = size.x
        params.height = size.y
        if (attached) wm.updateViewLayout(this, params) else wm.addView(this, params)
        attached = true
        refresh()
    }

    fun detach() {
        if (attached) runCatching { wm.removeViewImmediate(this) }
        attached = false
    }

    /** Draws [shown] in screen pixels. [modal] makes a touch anywhere dismiss it. */
    fun show(shown: List<Piece>, modal: Boolean) {
        if (shown.isEmpty()) {
            clear()
            return
        }
        pieces = shown
        extent.setEmpty()
        for (piece in shown) extent.union(piece.left, piece.top, piece.right, piece.bottom)
        passThrough = !modal
        refresh()
        animate().cancel()
        if (modal) {
            alpha = 0f
            animate().alpha(1f).setDuration(90).start()
        } else {
            alpha = 1f
        }
    }

    fun clear() {
        if (pieces.isNotEmpty()) clearedAt = SystemClock.uptimeMillis()
        pieces = emptyList()
        passThrough = true
        animate().cancel()
        alpha = 1f
        refresh()
    }

    private fun refresh() {
        if (!attached) return
        val empty = pieces.isEmpty() && area == null
        visibility = if (empty) GONE else VISIBLE
        val touchable = !passThrough && pieces.isNotEmpty()
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val opacity = if (touchable) 1f else passThroughOpacity()
        // The window's buffers are as big as the window: a whole screen of them is
        // ~40 MB. Only a modal layer needs the whole screen (a tap anywhere dismisses);
        // otherwise the window covers just what it draws.
        val bounds = Rect()
        if (touchable || empty) {
            bounds.set(screen)
        } else {
            if (pieces.isNotEmpty()) bounds.union(extent)
            area?.let { bounds.union(it) }
            if (!bounds.intersect(screen)) bounds.set(screen.left, screen.top, screen.left + 1, screen.top + 1)
        }
        val moved = bounds.left != params.x || bounds.top != params.y ||
            bounds.width() != params.width || bounds.height() != params.height
        if (flags != params.flags || opacity != params.alpha || moved) {
            params.flags = flags
            params.alpha = opacity
            params.x = bounds.left
            params.y = bounds.top
            params.width = bounds.width()
            params.height = bounds.height()
            // The system may have taken the window (the accessibility service went): nothing to move then.
            runCatching { wm.updateViewLayout(this, params) }
        }
        invalidate()
    }

    private fun passThroughOpacity(): Float =
        // An accessibility overlay is trusted: opaque, and touches still pass.
        if (ScreenOverlayHost.trusted) {
            1f
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
        } else {
            1f
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onDismiss()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // Whatever the system did with the window, draw in true screen coordinates.
        getLocationOnScreen(location)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        if (!passThrough && pieces.isNotEmpty() && scrim > 0) {
            scrimPaint.color = ((scrim.coerceIn(0, 90) * 255 / 100) shl 24)
            canvas.drawRect(screen, scrimPaint)
        }
        for (piece in pieces) {
            if (!piece.bitmap.isRecycled) canvas.drawBitmap(piece.bitmap, piece.left.toFloat(), piece.top.toFloat(), bitmapPaint)
        }
        area?.let { rect ->
            val half = framePaint.strokeWidth / 2
            framePaint.alpha = if (areaDimmed) 90 else 255
            canvas.drawRect(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half, framePaint)
        }
    }

    companion object {
        const val BORDER_DP = 2f

        /** The part of an area rectangle that is the user's content, clear of the frame drawn on it. */
        fun inner(area: Rect, density: Float): Rect {
            val inset = (BORDER_DP * density).roundToInt() + 2
            return Rect(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset)
        }
    }
}

/**
 * The floating button. A tap does what the current mode does, a long press
 * opens the menu, a drag moves it, and it comes to rest against the nearer
 * edge. Left alone for a few seconds it tucks itself most of the way past that
 * edge, so that it covers (and keeps Lens from reading) as little of the
 * screen as it can; a touch brings it back.
 *
 * Its window also listens for touches that land anywhere else: that is how the
 * translator knows a finger came down before the screen shows it.
 */
@SuppressLint("ViewConstructor")
internal class BubbleView(
    context: Context,
    private val wm: WindowManager,
    private val prefs: ScreenPrefs,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragging(dragging: Boolean)

        /**
         * Where the bubble is drawn changed: [footprint] holds it at rest,
         * tucked, and everywhere in between, so that sliding in and out of the
         * edge is not a change at all.
         */
        fun onMoved(footprint: Rect)

        /** A finger came down somewhere else on the screen. */
        fun onOutsideTouch()
    }

    enum class Look { Idle, Busy, Active, Paused, Error }

    private val density = resources.displayMetrics.density
    private val size = (56 * density).roundToInt()
    /** How much of the bubble stays in view while it is tucked. */
    private val peek = (26 * density).roundToInt()
    private val params = overlayParams(
        size, size,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    )
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_screen_translate)!!.mutate()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var screen = Point(1, 1)
    private var insets = Rect()
    private var attached = false
    private var slide: ValueAnimator? = null
    private var right = true
    private var tucked = false
    /** 0 in full view, 1 tucked against the edge. */
    private var tuck = 0f
    private var footprint = Rect()

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        tuck(false)
        listener.onLongPress()
    }
    private val tuckAway = Runnable { tuck(true) }

    var look: Look = Look.Idle
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The part of the bubble that is on screen now: what a captured frame has of it. */
    val bounds: Rect
        get() = Rect(params.x, params.y, params.x + size, params.y + size).apply {
            if (!intersect(0, 0, screen.x, screen.y)) setEmpty()
        }

    fun attach(screen: Point, insets: Rect) {
        this.screen = screen
        this.insets = insets
        slide?.cancel()
        right = prefs.bubbleRight
        tucked = false
        tuck = 0f
        params.x = restX(right, tucked = false)
        params.y = clampY((prefs.bubbleY * screen.y).toInt())
        if (attached) wm.updateViewLayout(this, params) else wm.addView(this, params)
        attached = true
        footprint = Rect()
        report()
        tuckLater()
    }

    fun detach() {
        slide?.cancel()
        removeCallbacks(longPress)
        removeCallbacks(tuckAway)
        if (attached) runCatching { wm.removeViewImmediate(this) }
        attached = false
    }

    /**
     * Out of the picture while a screenshot is taken, so that what is under
     * the bubble gets translated too. It is still there to be touched.
     */
    fun conceal() {
        animate().cancel()
        alpha = 0f
    }

    fun reveal() {
        animate().alpha(1f).setDuration(90).start()
    }

    /** A translation the user asked for by hand has arrived, or has not. */
    fun buzz(ok: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performHapticFeedback(if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
        }
    }

    private fun restX(right: Boolean, tucked: Boolean): Int {
        val margin = (4 * density).toInt()
        return when {
            tucked && right -> screen.x - peek
            tucked -> peek - size
            right -> screen.x - insets.right - size - margin
            else -> insets.left + margin
        }
    }

    /** Tucking slides the bubble past the screen's edge: only where no cutout or side bar lies in between. */
    private fun canTuck(right: Boolean): Boolean = if (right) insets.right == 0 else insets.left == 0

    private fun clampY(y: Int): Int {
        val margin = (8 * density).toInt()
        return y.coerceIn(insets.top + margin, max(insets.top + margin, screen.y - insets.bottom - size - margin))
    }

    private fun moveTo(x: Int, y: Int) {
        params.x = x
        params.y = y
        if (attached) runCatching { wm.updateViewLayout(this, params) }
        report()
    }

    /** Tells the listener where the bubble may be drawn, when that has changed. */
    private fun report() {
        val now = Rect(params.x, params.y, params.x + size, params.y + size)
        if (!dragging) {
            // At rest it also slides to and from the edge by itself: all of that is one place.
            val rest = restX(right, tucked = false)
            now.union(rest, params.y, rest + size, params.y + size)
            if (canTuck(right)) {
                val away = restX(right, tucked = true)
                now.union(away, params.y, away + size, params.y + size)
            }
        }
        if (!now.intersect(0, 0, screen.x, screen.y)) now.setEmpty()
        if (now == footprint) return
        footprint = now
        listener.onMoved(Rect(now))
    }

    private fun tuckLater() {
        removeCallbacks(tuckAway)
        if (attached && canTuck(right)) postDelayed(tuckAway, TUCK_AFTER_MS)
    }

    private fun tuck(on: Boolean) {
        if (!attached || dragging) return
        if (on && !canTuck(right)) return
        val to = restX(right, on)
        if (tucked == on && params.x == to) return
        tucked = on
        slideTo(to, if (on) 1f else 0f, 160) {}
    }

    private fun slideTo(x: Int, tuckTo: Float, duration: Long, done: () -> Unit) {
        slide?.cancel()
        val fromX = params.x
        val fromTuck = tuck
        slide = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                tuck = fromTuck + (tuckTo - fromTuck) * f
                moveTo((fromX + (x - fromX) * f).roundToInt(), params.y)
                invalidate()
            }
            // Also when cancelled (a new touch): whoever waits for the end must not wait for ever.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = done()
            })
            start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // By the edge the bubble sits in the back gesture's strip: a drag that starts on it is ours.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> listener.onOutsideTouch()
            MotionEvent.ACTION_DOWN -> {
                // Ends a slide in progress, which may schedule the next tuck: so that comes off after it.
                slide?.cancel()
                removeCallbacks(tuckAway)
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                longPressed = false
                postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(longPress)
                    slide?.cancel()
                    tucked = false
                    tuck = 0f
                    invalidate()
                    listener.onDragging(true)
                }
                if (dragging) {
                    moveTo(
                        (startX + dx).toInt().coerceIn(0, max(0, screen.x - size)),
                        clampY((startY + dy).toInt()),
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    settle()
                } else {
                    if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tuck(false)
                        listener.onTap()
                    }
                    tuckLater()
                }
            }
        }
        return true
    }

    /** Slides to the nearer side edge, quickly and without overshoot. */
    private fun settle() {
        right = params.x + size / 2 > screen.x / 2
        prefs.bubbleRight = right
        prefs.bubbleY = params.y.toFloat() / screen.y
        tucked = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        slideTo(restX(right, tucked = false), 0f, 120) {
            if (!dragging) return@slideTo
            dragging = false
            listener.onDragging(false)
            report()
            tuckLater()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f - 4 * density
        fill.color = when (look) {
            Look.Active -> 0xF05B5BD6.toInt()
            else -> 0xF0221F3D.toInt()
        }
        canvas.drawCircle(cx, cy, radius, fill)
        ring.color = when (look) {
            Look.Error -> 0xFFFF6B6B.toInt()
            Look.Active -> 0xFFFFFFFF.toInt()
            else -> 0x66FFFFFF
        }
        val edge = radius - ring.strokeWidth / 2
        if (look == Look.Busy) {
            ring.color = 0xFFFFFFFF.toInt()
            arc.set(cx - edge, cy - edge, cx + edge, cy + edge)
            val turn = (SystemClock.uptimeMillis() % 800L) / 800f * 360f
            canvas.drawArc(arc, turn, 250f, false, ring)
            postInvalidateOnAnimation()
        } else {
            canvas.drawCircle(cx, cy, edge, ring)
        }
        // Tucked, only a sliver by the edge shows: the icon moves into it, smaller.
        val half = (12 - 5 * tuck) * density
        val ix = cx + (if (right) -1 else 1) * tuck * (radius - 11 * density)
        icon.setBounds((ix - half).roundToInt(), (cy - half).roundToInt(), (ix + half).roundToInt(), (cy + half).roundToInt())
        icon.alpha = if (look == Look.Paused) 110 else 255
        icon.draw(canvas)
    }

    private companion object {
        /** Shorter than the system's: the menu is the only way to the modes. */
        const val LONG_PRESS_MS = 350L
        /** Left alone this long, the bubble tucks itself against the edge. */
        const val TUCK_AFTER_MS = 3_500L
    }
}

/**
 * The two grips of the area: one moves it, one resizes it. They are separate
 * small windows so that everything inside the area stays the app's to touch.
 */
internal class AreaHandles(
    private val context: Context,
    private val wm: WindowManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onAreaDragging(dragging: Boolean)
        fun onAreaChanged(area: Rect)
    }

    private val density = context.resources.displayMetrics.density
    private val size = (40 * density).roundToInt()
    private val minWidth = (120 * density).roundToInt()
    private val minHeight = (56 * density).roundToInt()
    private var screen = Point(1, 1)
    private var area = Rect()
    private var attached = false
    private val move = Grip(resize = false)
    private val resize = Grip(resize = true)

    fun attach(screen: Point, area: Rect) {
        this.screen = screen
        this.area = fit(Rect(area))
        if (!attached) {
            wm.addView(move, move.params)
            wm.addView(resize, resize.params)
            attached = true
        }
        place()
        listener.onAreaChanged(Rect(this.area))
    }

    fun detach() {
        if (attached) {
            runCatching { wm.removeViewImmediate(move) }
            runCatching { wm.removeViewImmediate(resize) }
        }
        attached = false
    }

    /** Keeps the rectangle a usable size and on screen, with room above it for the move grip. */
    private fun fit(rect: Rect): Rect {
        val width = rect.width().coerceIn(minWidth, max(minWidth, screen.x))
        val height = rect.height().coerceIn(minHeight, max(minHeight, screen.y - size))
        val left = rect.left.coerceIn(0, max(0, screen.x - width))
        val top = rect.top.coerceIn(size, max(size, screen.y - height))
        rect.set(left, top, left + width, top + height)
        return rect
    }

    private fun place() {
        if (!attached) return
        // Both grips sit outside the rectangle, so they are not in what gets translated.
        move.params.x = (area.centerX() - size / 2).coerceIn(0, max(0, screen.x - size))
        move.params.y = max(0, area.top - size)
        resize.params.x = min(area.right, screen.x - size)
        resize.params.y = min(area.bottom, screen.y - size)
        runCatching { wm.updateViewLayout(move, move.params) }
        runCatching { wm.updateViewLayout(resize, resize.params) }
    }

    @SuppressLint("ViewConstructor")
    private inner class Grip(private val resize: Boolean) : View(context) {
        val params = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0221F3D.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.ROUND
        }
        private var downX = 0f
        private var downY = 0f
        private val start = Rect()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    start.set(area)
                    listener.onAreaDragging(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    val next = Rect(start)
                    if (resize) {
                        next.right = (start.right + dx).coerceIn(start.left + minWidth, max(start.left + minWidth, screen.x))
                        next.bottom = (start.bottom + dy).coerceIn(start.top + minHeight, max(start.top + minHeight, screen.y))
                    } else {
                        next.offset(dx, dy)
                    }
                    area = fit(next)
                    place()
                    listener.onAreaChanged(Rect(area))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listener.onAreaDragging(false)
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            canvas.drawCircle(c, c, c - 3 * density, fill)
            val arm = 7 * density
            val head = 3 * density
            if (resize) {
                // A diagonal with a corner at each end.
                canvas.drawLine(c - arm, c - arm, c + arm, c + arm, line)
                canvas.drawLine(c + arm, c + arm, c + arm - 2 * head, c + arm, line)
                canvas.drawLine(c + arm, c + arm, c + arm, c + arm - 2 * head, line)
                canvas.drawLine(c - arm, c - arm, c - arm + 2 * head, c - arm, line)
                canvas.drawLine(c - arm, c - arm, c - arm, c - arm + 2 * head, line)
            } else {
                // A four-way arrow.
                canvas.drawLine(c - arm, c, c + arm, c, line)
                canvas.drawLine(c, c - arm, c, c + arm, line)
                for ((sx, sy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                    val tx = c + sx * arm
                    val ty = c + sy * arm
                    canvas.drawLine(tx, ty, tx - sx * head + sy * head, ty - sy * head + sx * head, line)
                    canvas.drawLine(tx, ty, tx - sx * head - sy * head, ty - sy * head - sx * head, line)
                }
            }
        }
    }
}

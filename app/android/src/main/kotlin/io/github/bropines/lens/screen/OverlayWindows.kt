package io.github.bropines.lens.screen

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
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

/**
 * The full-screen layer the translation is drawn on, plus the area's frame.
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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_DP * resources.displayMetrics.density
        color = 0xFF9E9BFF.toInt()
    }
    private val location = IntArray(2)
    private var bitmap: Bitmap? = null
    private val target = Rect()
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

    /** Dims the area's frame while the area is paused. */
    var areaDimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    val hasContent: Boolean get() = bitmap != null

    /** When our own drawing last left the screen: frames older than this may still show it. */
    var clearedAt: Long = 0L
        private set

    fun attach(size: Point) {
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

    /** Draws [image] over [at] (screen pixels). [modal] makes a touch anywhere dismiss it. */
    fun show(image: Bitmap, at: Rect, modal: Boolean) {
        bitmap = image
        target.set(at)
        passThrough = !modal
        refresh()
        if (modal) {
            alpha = 0f
            animate().alpha(1f).setDuration(90).start()
        }
    }

    fun clear() {
        if (bitmap != null) clearedAt = SystemClock.uptimeMillis()
        bitmap = null
        passThrough = true
        animate().cancel()
        alpha = 1f
        refresh()
    }

    private fun refresh() {
        if (!attached) return
        val empty = bitmap == null && area == null
        visibility = if (empty) GONE else VISIBLE
        val touchable = !passThrough && bitmap != null
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val opacity = if (touchable) 1f else passThroughOpacity()
        if (flags != params.flags || opacity != params.alpha) {
            params.flags = flags
            params.alpha = opacity
            wm.updateViewLayout(this, params)
        }
        invalidate()
    }

    private fun passThroughOpacity(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        bitmap?.let { canvas.drawBitmap(it, null, target, bitmapPaint) }
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
 * opens the menu, a drag moves it, and it comes to rest against the nearer edge.
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
        /** The bubble's rectangle on screen changed. */
        fun onMoved(bounds: Rect)
    }

    enum class Look { Idle, Busy, Active, Paused, Error }

    private val density = resources.displayMetrics.density
    private val size = (56 * density).roundToInt()
    private val params = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
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
    private var snap: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onLongPress()
    }

    var look: Look = Look.Idle
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val bounds: Rect get() = Rect(params.x, params.y, params.x + size, params.y + size)

    fun attach(screen: Point, insets: Rect) {
        this.screen = screen
        this.insets = insets
        params.x = restX(prefs.bubbleRight)
        params.y = clampY((prefs.bubbleY * screen.y).toInt())
        if (attached) wm.updateViewLayout(this, params) else wm.addView(this, params)
        attached = true
        listener.onMoved(bounds)
    }

    fun detach() {
        snap?.cancel()
        removeCallbacks(longPress)
        if (attached) runCatching { wm.removeViewImmediate(this) }
        attached = false
    }

    private fun restX(right: Boolean): Int {
        val margin = (4 * density).toInt()
        return if (right) screen.x - insets.right - size - margin else insets.left + margin
    }

    private fun clampY(y: Int): Int {
        val margin = (8 * density).toInt()
        return y.coerceIn(insets.top + margin, max(insets.top + margin, screen.y - insets.bottom - size - margin))
    }

    private fun moveTo(x: Int, y: Int) {
        params.x = x
        params.y = y
        if (attached) wm.updateViewLayout(this, params)
        listener.onMoved(bounds)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snap?.cancel()
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
                } else if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                    listener.onTap()
                }
            }
        }
        return true
    }

    /** Slides to the nearer side edge, quickly and without overshoot. */
    private fun settle() {
        val right = params.x + size / 2 > screen.x / 2
        prefs.bubbleRight = right
        prefs.bubbleY = params.y.toFloat() / screen.y
        val from = params.x
        val to = restX(right)
        snap = ValueAnimator.ofInt(from, to).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            addUpdateListener { moveTo(it.animatedValue as Int, params.y) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dragging = false
                    listener.onDragging(false)
                }
            })
            start()
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
        val half = (12 * density).roundToInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.alpha = if (look == Look.Paused) 110 else 255
        icon.draw(canvas)
    }

    private companion object {
        /** Shorter than the system's: the menu is the only way to the modes. */
        const val LONG_PRESS_MS = 350L
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
        val width = rect.width().coerceIn(minWidth, screen.x)
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
        wm.updateViewLayout(move, move.params)
        wm.updateViewLayout(resize, resize.params)
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
                        next.right = (start.right + dx).coerceIn(start.left + minWidth, screen.x)
                        next.bottom = (start.bottom + dy).coerceIn(start.top + minHeight, screen.y)
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

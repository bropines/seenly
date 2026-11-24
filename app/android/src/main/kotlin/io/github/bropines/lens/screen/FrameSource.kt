package io.github.bropines.lens.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/** Where the screen translator's frames come from: a screen recording, or the accessibility service's screenshots. */
internal interface FrameSource {
    val width: Int
    val height: Int

    /** The part of the screen the signature watches; null is all of it. */
    var roi: Rect?

    /** A rectangle that never counts as motion: the bubble, which is ours. */
    var exclude: Rect?

    val signature: Signature?

    /** `SystemClock.uptimeMillis()` of the last frame that differed from the one before it. */
    val lastMotionAt: Long

    /**
     * No later than the moment the latest frame was taken. Screenshots arrive
     * hundreds of milliseconds late, so whether a frame shows our own drawing
     * is decided by this, never by when it arrived.
     */
    val frameAt: Long

    /** Whether frames are needed all the time (live and area) or only on request ("this screen"). */
    var continuous: Boolean

    /** Restarts the stillness clock, as if the picture had just moved. */
    fun touch()

    /** Waits (briefly) for a frame taken after this call. A screen recording always has one. */
    suspend fun refresh()

    /** A mutable copy of the latest frame, or of [crop] out of it. Null before the first frame. */
    fun grab(crop: Rect? = null): Bitmap?

    fun resize(width: Int, height: Int, dpi: Int)

    fun close()
}

/** The brightness grid [Signature] keeps, read from any pixel source. */
internal fun sampleSignature(frameWidth: Int, frameHeight: Int, roi: Rect?, skip: Rect?, luma: (x: Int, y: Int) -> Int): Signature {
    val frame = Rect(0, 0, frameWidth, frameHeight)
    val region = Rect(roi ?: frame)
    if (!region.intersect(frame) || region.isEmpty) region.set(frame)
    val cols = Signature.COLS
    val rows = Signature.ROWS
    val xs = IntArray(cols) { region.left + ((it + 0.5f) * region.width() / cols).toInt() }
    val ys = IntArray(rows) { region.top + ((it + 0.5f) * region.height() / rows).toInt() }
    val cells = ByteArray(cols * rows)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if (skip != null && skip.contains(xs[c], ys[r])) continue
            cells[r * cols + c] = luma(xs[c], ys[r]).toByte()
        }
    }
    return Signature(region, cells, xs, ys)
}

/**
 * Frames from [AccessibilityService.takeScreenshot]: no screen-recording
 * consent to ask for every time. The system allows one screenshot every
 * 333 ms, so live and area modes see the screen at about three frames a
 * second, and "this screen" takes one only when asked.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AccessibilityCapturer(
    private val service: AccessibilityService,
    width: Int,
    height: Int,
) : FrameSource {
    private val thread = HandlerThread("lens-a11y-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Any()
    private var latest: Bitmap? = null
    private var inFlight = false
    private var closed = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()
    private val shoot = Runnable { shoot() }

    @Volatile override var width: Int = width
        private set
    @Volatile override var height: Int = height
        private set
    @Volatile override var roi: Rect? = null
    @Volatile override var exclude: Rect? = null
    @Volatile override var signature: Signature? = null
        private set
    @Volatile override var lastMotionAt: Long = SystemClock.uptimeMillis()
        private set
    @Volatile override var frameAt: Long = 0L
        private set

    @Volatile override var continuous: Boolean = false
        set(value) {
            field = value
            if (value) schedule(0)
        }

    override fun touch() {
        lastMotionAt = SystemClock.uptimeMillis()
    }

    override suspend fun refresh() {
        val fresh = CompletableDeferred<Unit>()
        synchronized(lock) { waiters += fresh }
        schedule(0)
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) { fresh.await() }
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(shoot)
        handler.postDelayed(shoot, delay)
    }

    /** Runs on the capture thread, like every callback here. */
    private fun shoot() {
        if (closed || inFlight) return
        inFlight = true
        val started = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY, executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    inFlight = false
                    val bitmap = runCatching {
                        result.hardwareBuffer.use { buffer ->
                            // A hardware bitmap cannot be read pixel by pixel: copy it to software once.
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                                hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                            }
                        }
                    }.getOrNull()
                    // Taken somewhere between the request and now: the request time is the safe bound.
                    if (bitmap != null) accept(bitmap, started)
                    next(INTERVAL_MS - (SystemClock.uptimeMillis() - started))
                }

                override fun onFailure(errorCode: Int) {
                    inFlight = false
                    // Too soon after the last one is the usual reason; anything else, back off.
                    next(if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) INTERVAL_MS else INTERVAL_MS * 3)
                }
            },
        )
    }

    private fun next(delay: Long) {
        val wanted = synchronized(lock) { continuous || waiters.isNotEmpty() }
        if (wanted && !closed) schedule(delay.coerceAtLeast(0))
    }

    private fun accept(bitmap: Bitmap, takenAt: Long) {
        val done: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            if (closed) {
                bitmap.recycle()
                return
            }
            latest?.recycle()
            latest = bitmap
            width = bitmap.width
            height = bitmap.height
            val next = sampleSignature(bitmap.width, bitmap.height, roi, exclude) { x, y ->
                val p = bitmap.getPixel(x, y)
                ((p shr 16 and 0xFF) + 2 * (p shr 8 and 0xFF) + (p and 0xFF)) shr 2
            }
            val previous = signature
            if (previous == null || next.diff(previous) >= MOTION_CELLS) lastMotionAt = takenAt
            signature = next
            frameAt = takenAt
            done = waiters.toList()
            waiters.clear()
        }
        done.forEach { it.complete(Unit) }
    }

    override fun grab(crop: Rect?): Bitmap? = synchronized(lock) {
        val frame = latest ?: return null
        val region = Rect(crop ?: Rect(0, 0, frame.width, frame.height))
        if (!region.intersect(0, 0, frame.width, frame.height) || region.isEmpty) return null
        // Always a copy: the caller paints on it and recycles it, and the latest frame is ours.
        if (region.width() == frame.width && region.height() == frame.height) return frame.copy(Bitmap.Config.ARGB_8888, true)
        val cut = Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height())
        if (cut.isMutable && cut !== frame) cut else cut.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== cut && cut !== frame) cut.recycle() }
    }

    override fun resize(width: Int, height: Int, dpi: Int) {
        synchronized(lock) {
            this.width = width
            this.height = height
            signature = null
        }
        touch()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest?.recycle()
            latest = null
            waiters.forEach { it.complete(Unit) }
            waiters.clear()
        }
        handler.removeCallbacks(shoot)
        thread.quitSafely()
    }

    private companion object {
        /** The system's minimum gap between two accessibility screenshots, plus a little. */
        const val INTERVAL_MS = 340L
        const val REFRESH_TIMEOUT_MS = 1_500L
        const val MOTION_CELLS = 4
    }
}

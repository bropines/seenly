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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/** A mutable copy of a frame's pixels (or of [region] out of them), and the frame they are of. */
internal class Grab(val bitmap: Bitmap, val frame: Frame, val region: Rect)

/** Where the screen translator's frames come from: a screen recording, or the accessibility service's screenshots. */
internal interface FrameSource {
    val width: Int
    val height: Int

    /** The part of the screen the signature watches; null is all of it. */
    var roi: Rect?

    /** A rectangle that never counts as motion: the bubble, which is ours. */
    var exclude: Rect?

    /** The latest frame; null before the first. */
    val frame: Frame?

    /**
     * True for a source that delivers a frame whenever the screen changes and
     * none while it is still (a recording); false for one that looks at its
     * own pace (screenshots).
     */
    val eventDriven: Boolean

    /** Whether frames are needed all the time (live and area) or only on request ("this screen"). */
    var continuous: Boolean

    /** Called on the capture thread whenever [frame] has changed. */
    var onFrame: (() -> Unit)?

    /** A paced source: no more than one frame every [intervalMs] while [continuous]. */
    fun pace(intervalMs: Long)

    /** A paced source: take a frame no earlier than [at] (uptime), and as soon after as the system allows. */
    fun hurry(at: Long)

    /** Waits (briefly) for a frame taken at [after] (uptime) or later. A recording has one as soon as that time has passed. */
    suspend fun refresh(after: Long = SystemClock.uptimeMillis())

    /** The latest frame's pixels: the part its signature watches ([watched]), or all of it. Null before the first frame. */
    fun grab(watched: Boolean): Grab?

    fun resize(width: Int, height: Int, dpi: Int)

    fun close()
}

internal fun Rect.toRegion(): Region = Region(left, top, right, bottom)

internal fun Region.toRect(): Rect = Rect(left, top, right, bottom)

/**
 * Frames from [AccessibilityService.takeScreenshot]: no screen-recording
 * consent to ask for every time. The system allows one screenshot every
 * 333 ms, so live and area modes see the screen at about three frames a
 * second at best (less often while nothing happens, see [pace]), and "this
 * screen" takes one only when asked.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AccessibilityCapturer(
    private val service: AccessibilityService,
    width: Int,
    height: Int,
) : FrameSource {
    private class Waiter(val after: Long, val done: CompletableDeferred<Unit>)

    private val thread = HandlerThread("lens-a11y-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Any()
    private var latest: Bitmap? = null
    private var count = 0L
    private val waiters = mutableListOf<Waiter>()
    private val shoot = Runnable { shoot() }

    // Touched on the capture thread only.
    private var inFlight = false
    private var lastShotAt = 0L
    private var errors = 0

    @Volatile private var closed = false
    @Volatile private var interval = MIN_INTERVAL_MS
    @Volatile private var wantedAt = 0L

    @Volatile override var width: Int = width
        private set
    @Volatile override var height: Int = height
        private set
    @Volatile override var frame: Frame? = null
        private set
    @Volatile override var onFrame: (() -> Unit)? = null

    override val eventDriven: Boolean get() = false

    @Volatile override var roi: Rect? = null
        set(value) {
            field = value
            handler.post { resample() }
        }

    @Volatile override var exclude: Rect? = null
        set(value) {
            field = value
            handler.post { resample() }
        }

    @Volatile override var continuous: Boolean = false
        set(value) {
            field = value
            if (value) replan()
        }

    override fun pace(intervalMs: Long) {
        val next = max(MIN_INTERVAL_MS, intervalMs)
        if (next == interval) return
        interval = next
        // A shorter interval should not wait out the long one already scheduled.
        replan()
    }

    override fun hurry(at: Long) {
        wantedAt = at
        replan()
    }

    override suspend fun refresh(after: Long) {
        delay((after - SystemClock.uptimeMillis()).coerceIn(0, 1_000))
        val fresh = CompletableDeferred<Unit>()
        synchronized(lock) { waiters += Waiter(after, fresh) }
        replan()
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) { fresh.await() }
        synchronized(lock) { waiters.removeAll { it.done === fresh } }
    }

    private fun replan() {
        if (closed) return
        handler.post {
            // A screenshot the system never answered must not stop all the ones after it.
            if (inFlight && SystemClock.uptimeMillis() - lastShotAt > STUCK_MS) inFlight = false
            if (!inFlight) plan()
        }
    }

    /** Schedules the next screenshot, or none if nobody wants one. Capture thread. */
    private fun plan() {
        handler.removeCallbacks(shoot)
        if (closed) return
        val waiting = synchronized(lock) { waiters.isNotEmpty() }
        if (!continuous && !waiting) return
        val now = SystemClock.uptimeMillis()
        // The system refuses a screenshot sooner than a third of a second after the last.
        val earliest = lastShotAt + MIN_INTERVAL_MS
        val due = when {
            waiting -> earliest
            // A frame is wanted at a given moment: one taken just before it would be of no use,
            // and would put the one that is off by a whole interval.
            wantedAt != 0L -> wantedAt
            else -> lastShotAt + interval * (1 + min(errors, 8))
        }
        handler.postDelayed(shoot, (max(due, earliest) - now).coerceAtLeast(0))
    }

    /** Runs on the capture thread, like every callback here. */
    private fun shoot() {
        if (closed || inFlight) return
        inFlight = true
        val started = SystemClock.uptimeMillis()
        lastShotAt = started
        if (wantedAt != 0L && started >= wantedAt) wantedAt = 0L
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        inFlight = false
                        errors = 0
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
                        plan()
                    }

                    override fun onFailure(errorCode: Int) {
                        inFlight = false
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            // Our clock and the system's disagreed by a hair: the same turn, a moment later.
                            lastShotAt = started - MIN_INTERVAL_MS + RETRY_SOON_MS
                        } else {
                            // The display is off, or the system cannot right now: ask less and less often.
                            errors++
                        }
                        plan()
                    }
                },
            )
        } catch (e: RuntimeException) {
            // The service is on its way out.
            inFlight = false
            errors++
            plan()
        }
    }

    private fun accept(bitmap: Bitmap, takenAt: Long) {
        val done: List<Waiter>
        synchronized(lock) {
            if (closed) {
                bitmap.recycle()
                return
            }
            latest?.recycle()
            latest = bitmap
            width = bitmap.width
            height = bitmap.height
            frame = Frame(sample(bitmap), takenAt, ++count)
            done = waiters.filter { takenAt >= it.after }
            waiters.removeAll(done)
        }
        done.forEach { it.done.complete(Unit) }
        onFrame?.invoke()
    }

    /** The watched region or the bubble moved: the frame in hand is looked at again as they are now. */
    private fun resample() {
        synchronized(lock) {
            val bitmap = latest ?: return
            val known = frame ?: return
            if (closed) return
            frame = Frame(sample(bitmap), known.takenAt, ++count)
        }
        onFrame?.invoke()
    }

    private fun sample(bitmap: Bitmap): Signature =
        sampleSignature(bitmap.width, bitmap.height, roi?.toRegion(), exclude?.toRegion()) { x, y ->
            val p = bitmap.getPixel(x, y)
            ((p shr 16 and 0xFF) + 2 * (p shr 8 and 0xFF) + (p and 0xFF)) shr 2
        }

    override fun grab(watched: Boolean): Grab? = synchronized(lock) {
        val source = latest ?: return null
        val of = frame ?: return null
        val region = if (watched) of.signature.roi.toRect() else Rect(0, 0, source.width, source.height)
        if (!region.intersect(0, 0, source.width, source.height) || region.isEmpty) return null
        // Always a copy: the caller paints on it and recycles it, and the latest frame is ours.
        val copy = runCatching {
            if (region.width() == source.width && region.height() == source.height) {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                val cut = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height())
                if (cut.isMutable && cut !== source) cut else cut.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== cut && cut !== source) cut.recycle() }
            }
        }.getOrNull() ?: return null
        Grab(copy, of, region)
    }

    override fun resize(width: Int, height: Int, dpi: Int) {
        synchronized(lock) {
            this.width = width
            this.height = height
            // The frame in hand is of the display as it was.
            latest?.recycle()
            latest = null
            frame = null
        }
        replan()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest?.recycle()
            latest = null
            waiters.forEach { it.done.complete(Unit) }
            waiters.clear()
        }
        onFrame = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private companion object {
        /** The system's minimum gap between two accessibility screenshots, plus a little. */
        const val MIN_INTERVAL_MS = LiveBrain.FAST_MS
        const val RETRY_SOON_MS = 40L
        const val REFRESH_TIMEOUT_MS = 1_500L
        const val STUCK_MS = 3_000L
    }
}

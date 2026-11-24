package io.github.bropines.lens.screen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * A frame reduced to a coarse grid of brightness samples: enough to tell
 * "the picture moved" from "the picture is the same", for almost nothing.
 *
 * [xs] and [ys] are the frame pixels the samples were read at, so a caller can
 * ask what else (its own overlay) sits on each of them.
 */
internal class Signature(val roi: Rect, val cells: ByteArray, val xs: IntArray, val ys: IntArray) {
    /**
     * How many samples differ visibly from [other]'s. With a [mask], only the
     * cells whose mask value is [maskValue] are compared. Signatures of
     * different regions are not comparable and count as entirely different.
     */
    fun diff(other: Signature, mask: BooleanArray? = null, maskValue: Boolean = false): Int {
        if (roi != other.roi || cells.size != other.cells.size) return Int.MAX_VALUE
        var count = 0
        for (i in cells.indices) {
            if (mask != null && mask[i] != maskValue) continue
            if (abs((cells[i].toInt() and 0xFF) - (other.cells[i].toInt() and 0xFF)) > TOLERANCE) count++
        }
        return count
    }

    companion object {
        const val COLS = 40
        const val ROWS = 72
        /** Brightness steps (of 255) a sample may drift before it counts as changed. */
        const val TOLERANCE = 10
    }
}

/**
 * The screen as a stream of frames. It holds on to the latest one (no copy),
 * keeps its [signature] and the time the picture last moved, and copies pixels
 * out only when [grab] asks.
 *
 * The system delivers a frame only when something on screen changed, so a
 * still screen costs nothing.
 */
internal class ScreenCapturer(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    dpi: Int,
    /** The user (or the system, e.g. on lock) ended the capture. Called on the main thread. */
    private val onStopped: () -> Unit,
) : FrameSource {
    private val thread = HandlerThread("lens-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var closed = false

    @Volatile override var width: Int = width
        private set
    @Volatile override var height: Int = height
        private set

    /** The part of the screen the signature watches; null is all of it. */
    @Volatile override var roi: Rect? = null

    /** A rectangle that never counts as motion: the bubble, which is ours. */
    @Volatile override var exclude: Rect? = null

    @Volatile override var signature: Signature? = null
        private set

    /** `SystemClock.uptimeMillis()` of the last frame that differed from the one before it. */
    @Volatile override var lastMotionAt: Long = SystemClock.uptimeMillis()
        private set
    @Volatile override var frameAt: Long = 0L
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post(onStopped)
        }
    }

    init {
        // Android 14+ insists on a callback before the display exists.
        projection.registerCallback(callback, handler)
        val first = newReader(width, height)
        reader = first
        display = projection.createVirtualDisplay(
            "lens-screen", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, first.surface, null, handler,
        )
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        // Each image is a whole screen (14 MB at 1280x2772): the one we hold plus one arriving is enough.
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        }

    private fun onFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(lock) {
            if (closed || source !== reader) {
                image.close()
                return
            }
            latest?.close()
            latest = image
            val next = runCatching { sample(image) }.getOrNull() ?: return
            val previous = signature
            if (previous == null || next.diff(previous) >= MOTION_CELLS) lastMotionAt = SystemClock.uptimeMillis()
            signature = next
            // A recording delivers frames within a vsync or two: arrival is close enough.
            frameAt = SystemClock.uptimeMillis()
        }
    }

    private fun sample(image: Image): Signature {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val frame = Rect(0, 0, image.width, image.height)
        val region = Rect(roi ?: frame)
        if (!region.intersect(frame) || region.isEmpty) region.set(frame)
        val skip = exclude
        val cols = Signature.COLS
        val rows = Signature.ROWS
        val xs = IntArray(cols) { region.left + ((it + 0.5f) * region.width() / cols).toInt() }
        val ys = IntArray(rows) { region.top + ((it + 0.5f) * region.height() / rows).toInt() }
        val cells = ByteArray(cols * rows)
        val limit = buffer.limit()
        for (r in 0 until rows) {
            val y = ys[r]
            for (c in 0 until cols) {
                val x = xs[c]
                if (skip != null && skip.contains(x, y)) continue
                val at = y * rowStride + x * pixelStride
                if (at + 2 >= limit) continue
                val red = buffer.get(at).toInt() and 0xFF
                val green = buffer.get(at + 1).toInt() and 0xFF
                val blue = buffer.get(at + 2).toInt() and 0xFF
                cells[r * cols + c] = ((red + 2 * green + blue) shr 2).toByte()
            }
        }
        return Signature(region, cells, xs, ys)
    }

    /** Restarts the stillness clock, as if the picture had just moved. */
    /** The recording delivers every change anyway: nothing to switch. */
    override var continuous: Boolean = false

    /** The latest frame is always current: the system sends one whenever the screen changes. */
    override suspend fun refresh() = Unit

    override fun touch() {
        lastMotionAt = SystemClock.uptimeMillis()
    }

    /** A mutable copy of the latest frame, or of [crop] out of it. Null before the first frame. */
    override fun grab(crop: Rect?): Bitmap? = synchronized(lock) {
        val image = latest ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            // Rows may be padded: copy the padded frame, then cut the real one out of it.
            val padded = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
            if (buffer.remaining() >= full.byteCount) {
                full.copyPixelsFromBuffer(buffer)
            } else {
                // The last row often comes without its padding.
                val whole = ByteBuffer.allocate(full.byteCount)
                whole.put(buffer)
                whole.rewind()
                full.copyPixelsFromBuffer(whole)
            }
            val region = Rect(crop ?: Rect(0, 0, image.width, image.height))
            if (!region.intersect(0, 0, image.width, image.height) || region.isEmpty) return null
            if (region.width() == full.width && region.height() == full.height) return full
            val cut = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height())
            if (cut !== full) full.recycle()
            if (cut.isMutable) cut else cut.copy(Bitmap.Config.ARGB_8888, true).also { cut.recycle() }
        } catch (e: Exception) {
            null
        }
    }

    /** The display changed size (a rotation): keep the same projection, swap the surface. */
    override fun resize(width: Int, height: Int, dpi: Int) {
        synchronized(lock) {
            if (closed || (width == this.width && height == this.height)) return
            val old = reader
            latest?.close()
            latest = null
            signature = null
            val next = newReader(width, height)
            reader = next
            this.width = width
            this.height = height
            runCatching {
                display?.resize(width, height, dpi)
                display?.surface = next.surface
            }
            old?.close()
            touch()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest?.close()
            latest = null
            runCatching { display?.release() }
            runCatching { reader?.close() }
            display = null
            reader = null
        }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        thread.quitSafely()
    }

    private companion object {
        /** Fewer changed samples than this between two frames is a clock tick or a cursor, not motion. */
        const val MOTION_CELLS = 4
    }
}

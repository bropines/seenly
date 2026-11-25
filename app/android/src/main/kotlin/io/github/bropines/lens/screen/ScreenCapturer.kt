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
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * The screen as a stream of frames. It holds on to the latest one (no copy),
 * keeps its signature, and copies pixels out only when [grab] asks.
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
    private var count = 0L
    @Volatile private var closed = false

    @Volatile override var width: Int = width
        private set
    @Volatile override var height: Int = height
        private set
    @Volatile override var frame: Frame? = null
        private set
    @Volatile override var onFrame: (() -> Unit)? = null

    override val eventDriven: Boolean get() = true

    /** The part of the screen the signature watches; null is all of it. */
    @Volatile override var roi: Rect? = null
        set(value) {
            field = value
            resampleSoon()
        }

    /** A rectangle that never counts as motion: the bubble, which is ours. */
    @Volatile override var exclude: Rect? = null
        set(value) {
            field = value
            resampleSoon()
        }

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
            reader.setOnImageAvailableListener({ onImage(it) }, handler)
        }

    private fun onImage(source: ImageReader) {
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
            // A recording delivers frames within a vsync or two: arrival is close enough.
            frame = Frame(next, SystemClock.uptimeMillis(), ++count)
        }
        onFrame?.invoke()
    }

    /**
     * The watched region or the bubble moved. A recording sends no frame for
     * that (nothing on screen changed), so the one in hand is looked at again
     * as they are now; otherwise the old region would be translated until the
     * screen next moved.
     */
    private fun resampleSoon() {
        if (closed) return
        handler.post {
            synchronized(lock) {
                val image = latest ?: return@post
                val known = frame ?: return@post
                if (closed) return@post
                val next = runCatching { sample(image) }.getOrNull() ?: return@post
                frame = Frame(next, known.takenAt, ++count)
            }
            onFrame?.invoke()
        }
    }

    private fun sample(image: Image): Signature {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        return sampleSignature(image.width, image.height, roi?.toRegion(), exclude?.toRegion()) { x, y ->
            val at = y * rowStride + x * pixelStride
            if (at + 2 >= limit) {
                0
            } else {
                val red = buffer.get(at).toInt() and 0xFF
                val green = buffer.get(at + 1).toInt() and 0xFF
                val blue = buffer.get(at + 2).toInt() and 0xFF
                (red + 2 * green + blue) shr 2
            }
        }
    }

    /** The recording delivers every change anyway: nothing to switch, pace or hurry. */
    override var continuous: Boolean = false

    override fun pace(intervalMs: Long) = Unit

    override fun hurry(at: Long) = Unit

    /** The latest frame is the screen as it is: the system sends one whenever the screen changes. */
    override suspend fun refresh(after: Long) {
        delay((after + LiveBrain.DELIVERY_MS - SystemClock.uptimeMillis()).coerceIn(0, 1_000))
    }

    override fun grab(watched: Boolean): Grab? = synchronized(lock) {
        val image = latest ?: return null
        val of = frame ?: return null
        try {
            val region = if (watched) of.signature.roi.toRect() else Rect(0, 0, image.width, image.height)
            if (!region.intersect(0, 0, image.width, image.height) || region.isEmpty) return null
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
            if (region.width() == full.width && region.height() == full.height) return Grab(full, of, region)
            val cut = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height())
            if (cut !== full) full.recycle()
            Grab(if (cut.isMutable) cut else cut.copy(Bitmap.Config.ARGB_8888, true).also { cut.recycle() }, of, region)
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
            frame = null
            val next = newReader(width, height)
            reader = next
            this.width = width
            this.height = height
            runCatching {
                display?.resize(width, height, dpi)
                display?.surface = next.surface
            }
            old?.close()
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
        onFrame = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        thread.quitSafely()
    }
}

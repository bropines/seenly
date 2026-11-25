package io.github.bropines.lens.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.bropines.lens.AppLocale
import io.github.bropines.lens.R
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.viewer.overlayStyle
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.decodeOverlay
import io.github.bropines.lens.overlay.renderTranslatedBitmap
import io.github.bropines.lens.protocol.ImagePreparer
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.Tiling
import io.github.bropines.lens.protocol.Raster
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The screen translator's brain: owns the overlay windows, decides when a
 * frame is worth sending to Lens, and puts the answer on screen.
 *
 * Everything here runs on the main thread except the pixel work, which
 * [translate] moves off it. There is never more than one Lens request in
 * flight: the one-shot mode cancels its predecessor, and the live loop only
 * asks again once its request has finished or been cancelled and joined.
 */
internal class ScreenTranslator(
    private val service: Context,
    /** A context that may own windows (a window context on Android 11+). */
    private val ui: Context,
    private val graph: AppGraph,
    private val capturer: FrameSource,
    private val stop: () -> Unit,
) : BubbleView.Listener, AreaHandles.Listener, BubbleMenu.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm = ui.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ui.resources.displayMetrics.density
    private val prefs = ScreenPrefs(service)
    private val fonts = createFontFamilyResolver(service)

    private val layer = LayerView(ui, wm, onDismiss = ::dismiss)
    private val bubble = BubbleView(ui, wm, prefs, this)
    private val handles = AreaHandles(ui, wm, this)
    private val menu = BubbleMenu(ui, wm, graph.settings, this)

    private var screen = Point(capturer.width, capturer.height)
    private var mode = prefs.mode
    private var settings = AppSettings()
    private var paused = false
    private var dragging = false
    private var failed = false
    private var area = Rect()
    private var once: Job? = null
    /** Counts one-shot runs, so a cancelled one does not tidy up after its successor. */
    private var run = 0
    private var live: Job? = null
    /**
     * The last few views translated and what came of them, newest last: a view
     * seen a moment ago is shown again from here, never sent to Lens twice.
     * Bounded by count; each layer holds only the rectangle it changed.
     */
    private val recent = ArrayDeque<Cached>()
    /** The current minimum time between two requests; grows while they come back to back. */
    private var gap = MIN_GAP_MS

    private fun remember(entry: Cached) {
        recent.removeAll { it.settings == entry.settings && it.signature.diff(entry.signature) < CHANGED_CELLS }
        recent.addLast(entry)
        while (recent.size > RECENT) recent.removeFirst()
    }
    /** Until then our own windows may still be in the frames. */
    private var dirtyUntil = 0L

    /** A translation ready to draw: [bitmap] (null when there was nothing to translate) goes over [rect]. */
    private class Layer(val bitmap: Bitmap?, val rect: Rect)

    private class Cached(val signature: Signature, val settings: AppSettings, val layer: Layer)

    init {
        scope.launch { graph.settings.settings.collect { settings = it } }
        // Bottom to top: the translation, the area's grips, the bubble (the menu comes and goes above them).
        layer.attach(screen)
        bubble.attach(screen, wm.barInsets(density))
        applyMode()
        if (!prefs.menuShown) {
            prefs.menuShown = true
            openMenu()
        }
    }

    fun close() {
        scope.cancel()
        menu.hide()
        handles.detach()
        bubble.detach()
        layer.detach()
    }

    /** The display may have rotated or changed size. */
    fun onDisplayChanged(dpi: Int) {
        val size = wm.screenSize()
        if (size == screen) return
        screen = size
        recent.clear()
        layer.clear()
        capturer.resize(size.x, size.y, dpi)
        layer.attach(size)
        bubble.attach(size, wm.barInsets(density))
        applyMode()
    }

    // ---- modes ----

    private fun applyMode() {
        once?.cancel()
        live?.cancel()
        layer.clear()
        recent.clear()
        gap = MIN_GAP_MS
        failed = false
        if (mode == ScreenMode.Area) {
            // The grips report the fitted rectangle back through onAreaChanged.
            handles.attach(screen, prefs.area(screen.x, screen.y))
            capturer.roi = LayerView.inner(area, density)
        } else {
            handles.detach()
            layer.area = null
            capturer.roi = null
        }
        capturer.touch()
        // Screenshots cost something each: "this screen" takes one only when tapped.
        capturer.continuous = mode != ScreenMode.Screen
        if (mode != ScreenMode.Screen) live = scope.launch { liveLoop() }
        updateBubble(busy = false)
    }

    private fun updateBubble(busy: Boolean) {
        bubble.look = when {
            busy -> BubbleView.Look.Busy
            failed -> BubbleView.Look.Error
            mode == ScreenMode.Screen -> if (layer.hasContent) BubbleView.Look.Active else BubbleView.Look.Idle
            paused -> BubbleView.Look.Paused
            else -> BubbleView.Look.Active
        }
        layer.areaDimmed = paused
    }

    override fun onTap() {
        when (mode) {
            ScreenMode.Screen -> when {
                once?.isActive == true -> {
                    once?.cancel()
                    updateBubble(busy = false)
                }
                layer.hasContent -> dismiss()
                else -> translateOnce()
            }
            else -> {
                paused = !paused
                failed = false
                updateBubble(busy = false)
            }
        }
    }

    override fun onLongPress() = openMenu()

    private fun openMenu() {
        once?.cancel()
        if (mode == ScreenMode.Screen) layer.clear()
        updateBubble(busy = false)
        menu.show(mode, wm.barInsets(density).bottom)
    }

    override fun onMode(mode: ScreenMode) {
        if (mode == this.mode) return
        this.mode = mode
        prefs.mode = mode
        paused = false
        applyMode()
        // The area grips come in their own window, which would cover the menu.
        if (menu.isShown) menu.raise()
    }

    override fun onPrimary() {
        paused = false
        if (mode == ScreenMode.Screen) translateOnce() else updateBubble(busy = false)
    }

    override fun onLanguage(source: Boolean, code: String?) {
        scope.launch {
            graph.settings.update {
                if (source) it.copy(sourceLanguage = code) else it.copy(targetLanguage = code ?: it.targetLanguage)
            }
        }
    }

    override fun onStop() = stop()

    override fun onMenuClosed() {
        dirtyUntil = SystemClock.uptimeMillis() + SETTLE_OWN_MS
        capturer.touch()
    }

    override fun onDragging(dragging: Boolean) {
        this.dragging = dragging
        if (!dragging) capturer.touch()
    }

    override fun onMoved(bounds: Rect) {
        capturer.exclude = bounds
    }

    override fun onAreaDragging(dragging: Boolean) {
        this.dragging = dragging
        if (!dragging) {
            prefs.saveArea(area, screen.x, screen.y)
            capturer.roi = LayerView.inner(area, density)
            capturer.touch()
        }
    }

    override fun onAreaChanged(area: Rect) {
        this.area = area
        layer.area = area
    }

    private fun dismiss() {
        once?.cancel()
        layer.clear()
        updateBubble(busy = false)
    }

    // ---- "this screen": one shot ----

    private fun translateOnce() {
        once?.cancel()
        failed = false
        val ticket = ++run
        once = scope.launch {
            layer.clear()
            updateBubble(busy = true)
            try {
                // Whatever of ours was on screen has to be gone from the frames first.
                val clean = max(dirtyUntil, layer.clearedAt + SETTLE_OWN_MS)
                delay((clean - SystemClock.uptimeMillis()).coerceIn(0, SETTLE_OWN_MS))
                val current = graph.settings.settings.first()
                capturer.refresh()
                val frame = grabClean(null)
                if (frame == null) {
                    toast(R.string.screen_capture_failed)
                    return@launch
                }
                val result = translate(frame, Rect(0, 0, screen.x, screen.y), current)
                val bitmap = result.bitmap
                if (bitmap == null) toast(R.string.screen_no_text) else layer.show(bitmap, result.rect, modal = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                if (ticket == run) updateBubble(busy = false)
            }
        }
    }

    // ---- live and area: translate whenever the picture comes to rest ----

    private enum class Phase { Waiting, Requesting, Shown }

    private suspend fun liveLoop() {
        var phase = Phase.Waiting
        var request: Deferred<Result<Layer>>? = null
        /** The clean frame the request was made from (and the shown translation belongs to). */
        var sent: Signature? = null
        var sentSettings = settings
        /** The same view with our translation over it, once that has settled. */
        var reference: Signature? = null
        /** Which samples our translation paints over. */
        var covered: BooleanArray? = null
        var shownAt = 0L
        var hiddenAt = SystemClock.uptimeMillis()
        var requestedAt = 0L
        try {
            while (true) {
                delay(POLL_MS)
                val now = SystemClock.uptimeMillis()
                if (paused || dragging || menu.isShown) {
                    // Out of the way while the user is busy with us; start over afterwards.
                    if (phase != Phase.Waiting) {
                        request?.cancelAndJoin()
                        request = null
                        layer.clear()
                        phase = Phase.Waiting
                        updateBubble(busy = false)
                    }
                    hiddenAt = now
                    continue
                }
                val signature = capturer.signature ?: continue
                when (phase) {
                    Phase.Shown -> {
                        val base = sent
                        var moved = base == null || settings != sentSettings ||
                            signature.diff(base, covered, maskValue = false) >= CHANGED_CELLS
                        if (!moved) {
                            val settled = reference
                            // Only a frame taken after the translation went up can show it: screenshots
                            // arrive late, and an older one would look as if the view had changed.
                            val sinceShown = capturer.frameAt - shownAt
                            if (settled != null) {
                                moved = signature.diff(settled, covered, maskValue = true) >= CHANGED_CELLS
                            } else if (sinceShown >= SETTLE_OWN_MS && now - capturer.lastMotionAt >= 60) {
                                reference = signature
                            } else if (sinceShown >= 500) {
                                // Still moving under the translation half a second on: it is stale.
                                moved = true
                            }
                        }
                        if (moved) {
                            // The view is changing: the translation no longer belongs to it.
                            layer.clear()
                            failed = false
                            updateBubble(busy = false)
                            phase = Phase.Waiting
                            hiddenAt = now
                        }
                    }
                    Phase.Waiting -> {
                        val still = now - capturer.lastMotionAt >= STILL_MS
                        // The frame must be one taken after our translation left the screen.
                        val clean = capturer.frameAt >= max(hiddenAt, dirtyUntil) + SETTLE_OWN_MS
                        if (!still || !clean || now < dirtyUntil || now - requestedAt < gap) continue
                        val known = recent.lastOrNull { it.settings == settings && signature.diff(it.signature) < CHANGED_CELLS }
                        if (known != null) {
                            // A view translated a moment ago (scrolled away and back, or just un-paused):
                            // shown again from memory, never asked of Lens a second time.
                            sent = known.signature
                            sentSettings = known.settings
                            covered = show(known.layer, known.signature)
                            reference = null
                            shownAt = now
                            phase = Phase.Shown
                            continue
                        }
                        val region = Rect(signature.roi)
                        val frame = grabClean(region) ?: continue
                        val asked = settings
                        sent = signature
                        sentSettings = asked
                        // Requests back to back mean something keeps changing the view on its own
                        // (an animation, or a loop through our own drawing): slow down, up to 8 s.
                        gap = if (now - requestedAt < BURST_MS) min(gap * 2, MAX_GAP_MS) else MIN_GAP_MS
                        requestedAt = now
                        Log.d(TAG, "live request, next gap ${gap} ms")
                        failed = false
                        updateBubble(busy = true)
                        request = scope.async { runCatching { translate(frame, region, asked) } }
                        phase = Phase.Requesting
                    }
                    Phase.Requesting -> {
                        val pending = request ?: continue
                        val base = sent
                        if (base == null || signature.diff(base) >= CHANGED_CELLS) {
                            // The user moved on before the answer came: it would land on the wrong picture.
                            pending.cancelAndJoin()
                            request = null
                            updateBubble(busy = false)
                            phase = Phase.Waiting
                        } else if (pending.isCompleted) {
                            request = null
                            val outcome = pending.await()
                            val result = outcome.getOrNull()
                            if (result != null) {
                                remember(Cached(base, sentSettings, result))
                                covered = show(result, base)
                            } else {
                                // Shown, with nothing on screen: it is tried again when the view changes.
                                covered = null
                                outcome.exceptionOrNull()?.let { if (it !is CancellationException) report(it) }
                            }
                            reference = null
                            shownAt = now
                            phase = Phase.Shown
                            updateBubble(busy = false)
                        }
                    }
                }
            }
        } finally {
            request?.cancel()
        }
    }

    /** Puts [result] on the pass-through layer and says which of [signature]'s samples it paints over. */
    private fun show(result: Layer, signature: Signature): BooleanArray? {
        val bitmap = result.bitmap ?: return null
        layer.show(bitmap, result.rect, modal = false)
        val covered = BooleanArray(signature.cells.size)
        val cols = signature.xs.size
        for (r in signature.ys.indices) {
            val y = signature.ys[r] - result.rect.top
            if (y < 0 || y >= bitmap.height) continue
            for (c in 0 until cols) {
                val x = signature.xs[c] - result.rect.left
                if (x < 0 || x >= bitmap.width) continue
                covered[r * cols + c] = bitmap.getPixel(x, y) ushr 24 != 0
            }
        }
        return covered
    }

    // ---- one frame through Lens ----

    /** The latest frame (or [region] of it) with the bubble painted out, so Lens never reads it. */
    private fun grabClean(region: Rect?): Bitmap? {
        val frame = capturer.grab(region) ?: return null
        val hole = Rect(bubble.bounds)
        hole.inset(-4, -4)
        hole.offset(-(region?.left ?: 0), -(region?.top ?: 0))
        if (!hole.intersect(0, 0, frame.width, frame.height)) return frame
        // Fill with the colour around it: a flat patch has nothing to recognise.
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        fun add(x: Int, y: Int) {
            val p = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
            red += p shr 16 and 0xFF
            green += p shr 8 and 0xFF
            blue += p and 0xFF
            count++
        }
        var x = hole.left
        while (x < hole.right) {
            add(x, hole.top - 1)
            add(x, hole.bottom)
            x += 4
        }
        var y = hole.top
        while (y < hole.bottom) {
            add(hole.left - 1, y)
            add(hole.right, y)
            y += 4
        }
        val paint = Paint().apply {
            color = (0xFF shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
        }
        Canvas(frame).drawRect(hole, paint)
        return frame
    }

    /**
     * Sends [frame] to Lens and renders the translation as a transparent layer
     * of the same size: only the pixels the translation changes are kept, so
     * everything else on screen stays live underneath.
     */
    private suspend fun translate(frame: Bitmap, rect: Rect, settings: AppSettings): Layer =
        // The frame is a whole screen of pixels: give it back as soon as it has served.
        try {
            translateFrame(frame, rect, settings)
        } finally {
            frame.recycle()
        }

    private suspend fun translateFrame(frame: Bitmap, rect: Rect, settings: AppSettings): Layer {
        // In bands, so a tall screen reaches Lens at full resolution rather than shrunk to 1600 px.
        val raster = withContext(Dispatchers.Default) {
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            Raster(frame.width, frame.height, pixels)
        }
        val scanned = Tiling.scan(raster) { band ->
            val prepared = withContext(Dispatchers.Default) { ImagePreparer.prepare(band) }
            // Lens, or the device where the build and the settings have it stand in.
            graph.scans.scanFrame(prepared, settings)
        }
        // The frame is the screen pixel for pixel, so the status bar is its top rows.
        val statusBar = (wm.barInsets(density).top - rect.top).toFloat() / frame.height
        // Inside an area, text running into the frame is a piece of a line the frame cuts.
        val cut = rect.width() < screen.x || rect.height() < screen.y
        val result = scanned.copy(
            paragraphs = scanned.paragraphs.filter { p ->
                worthTranslating(p, statusBar) && !(cut && touchesEdge(p, frame.width, frame.height))
            },
        )
        if (result.paragraphs.none { it.translation != null }) return Layer(null, rect)
        val decoded = decodeOverlay(result, fonts)
        return withContext(Dispatchers.Default) {
            val style = styleFor(result, settings)
            val rendered = renderTranslatedBitmap(
                frame.asImageBitmap(), result, style, decoded,
                // Sizes are real here: the frame is shown pixel for pixel.
                minFontPx = style.minFontSp * ui.resources.displayMetrics.scaledDensity,
            ).asAndroidBitmap()
            // Keep only the rectangle the translation changed: a screenful of mostly
            // transparent pixels would cost as much memory as the screen itself.
            val changed = keepChanges(rendered, frame)
            if (changed == null) {
                rendered.recycle()
                return@withContext Layer(null, rect)
            }
            val kept = if (changed.width() == rendered.width && changed.height() == rendered.height) {
                rendered
            } else {
                Bitmap.createBitmap(rendered, changed.left, changed.top, changed.width(), changed.height()).also { rendered.recycle() }
            }
            Layer(kept, Rect(changed).apply { offset(rect.left, rect.top) })
        }
    }

    /**
     * Not the status bar ("VoLTE", "4G+" made into words) and not a lone
     * character, which on a screen is nearly always an icon (an "i" that
     * came back as "я", the 文 of a translate icon).
     */
    private fun worthTranslating(p: Paragraph, statusBar: Float): Boolean {
        if (p.text.trim().length <= 1) return false
        val box = p.box ?: return true
        return box.centerY + box.height / 2 > statusBar
    }

    /** Whether [p]'s box reaches within a few pixels of the frame's edge. */
    private fun touchesEdge(p: Paragraph, width: Int, height: Int): Boolean {
        val box = p.box ?: return false
        val mx = EDGE_PX / width
        val my = EDGE_PX / height
        return box.centerX - box.width / 2 < mx || box.centerX + box.width / 2 > 1 - mx ||
            box.centerY - box.height / 2 < my || box.centerY + box.height / 2 > 1 - my
    }

    /** The same choice the viewer makes: bubbles reflow, everything else is set in place. */
    private fun styleFor(result: ScanResult, settings: AppSettings): OverlayStyle = settings.overlayStyle(result)

    /**
     * Makes every pixel of [rendered] that equals [frame]'s transparent and
     * returns the bounds of what is left, or null if nothing is.
     */
    private fun keepChanges(rendered: Bitmap, frame: Bitmap): Rect? {
        val width = rendered.width
        val height = rendered.height
        val rows = 64
        val drawn = IntArray(width * rows)
        val original = IntArray(width * rows)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var y = 0
        while (y < height) {
            val n = min(rows, height - y)
            rendered.getPixels(drawn, 0, width, 0, y, width, n)
            frame.getPixels(original, 0, width, 0, y, width, n)
            for (i in 0 until width * n) {
                if (drawn[i] == original[i]) {
                    drawn[i] = 0
                } else {
                    val x = i % width
                    val row = y + i / width
                    if (x < left) left = x
                    if (x > right) right = x
                    if (row < top) top = row
                    if (row > bottom) bottom = row
                }
            }
            rendered.setPixels(drawn, 0, width, 0, y, width, n)
            y += n
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    private fun report(error: Throwable) {
        Log.w(TAG, "translation failed", error)
        failed = true
        toast(R.string.screen_translate_failed, error.message ?: error::class.simpleName.orEmpty())
    }

    private fun toast(@StringRes text: Int, vararg args: Any) =
        Toast.makeText(service, AppLocale.string(service, text, *args), Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ScreenTranslator"
        /** How close to an area's edge counts as cut by it. */
        const val EDGE_PX = 6f
        const val POLL_MS = 24L
        /** How long the picture must hold still before it is translated. */
        const val STILL_MS = 200L
        /** How long our own windows take to leave the captured frames after we hide them. */
        const val SETTLE_OWN_MS = 100L
        /** No two requests closer than this, however restless the screen. */
        const val MIN_GAP_MS = 400L
        /** Requests closer together than this count as a burst, which doubles the gap. */
        const val BURST_MS = 3_000L
        const val MAX_GAP_MS = 8_000L
        /** Views remembered with their translations. */
        const val RECENT = 6
        /** This many changed samples (of 2880) mean the view is a different one. */
        const val CHANGED_CELLS = 12
    }
}

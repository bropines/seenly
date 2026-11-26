package io.github.bropines.lens.screen

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.content.Intent
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
import io.github.bropines.lens.app.data.OnDevicePrefs
import io.github.bropines.lens.app.data.OverlayPrefs
import io.github.bropines.lens.app.data.ReadingLayout
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The screen translator's hands: owns the overlay windows, takes frames,
 * sends them to Lens and puts the answer on screen.
 *
 * When a frame is worth sending, and when a translation has to come down, is
 * decided by [LiveBrain] (live and area modes); this class carries it out.
 * Everything here runs on the main thread except the pixel work, which
 * [translate] moves off it. There is never more than one Lens request in
 * flight: the one-shot mode cancels its predecessor, and the brain asks again
 * only once its request has finished or been cancelled.
 */
internal class ScreenTranslator(
    private val service: Context,
    /** A context that may own windows (a window context on Android 11+). */
    private val ui: Context,
    private val graph: AppGraph,
    private val capturer: FrameSource,
    /** Whether the screen is on and unlocked: asked now and then while it was last known not to be. */
    private val awake: () -> Boolean,
    private val stop: () -> Unit,
) : BubbleView.Listener, AreaHandles.Listener, BubbleMenu.Listener, LiveBrain.Shell {
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
    private var bars = wm.barInsets(density)
    private var mode = prefs.mode
    private var settings = AppSettings()
    private var paused = false
    private var dragging = false
    private var asleep = false
    private var busy = false
    private var failed = false
    private var area = Rect()
    /** Everywhere the bubble may be drawn while it rests: never read as part of the screen. */
    private var lane = Rect()
    private var once: Job? = null
    /** Counts one-shot runs, so a cancelled one does not tidy up after its successor. */
    private var run = 0
    private var live: Job? = null
    private var brain: LiveBrain? = null
    /** Views translated lately, shared by all three modes: a screen translated by hand is known to "auto" too. */
    private val memory = Memory(MEMORY_VIEWS, memoryBudget())
    /** Rouses the live loop: a frame came, or something it watches changed. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The live views being translated, by the brain's ticket: the work, the word to send it, what to show meanwhile. */
    private val requests = HashMap<Int, Job>()
    private val gates = HashMap<Int, CompletableDeferred<Unit>>()
    private val previews = HashMap<Int, List<Piece>>()
    /** What the last translation shown says, for the menu's "copy". */
    private var texts: Texts? = null
    /** Until then our own windows may still be in the frames. */
    private var dirtyUntil = 0L

    private class Texts(val translation: String, val original: String)

    /** One band of a rendered translation, in the pixels of the frame it was made from. */
    private class Tile(val bitmap: Bitmap, val left: Int, val top: Int)

    /** A translation ready to draw, and what it takes to put it back after the page under it moves. */
    private class Stored(
        /** Where on screen the frame it was made from sits. */
        val region: Rect,
        val tiles: List<Tile>,
        /** Of that frame, before anything was drawn on it. */
        val print: RowPrint,
        val texts: Texts,
    ) {
        val bytes: Long get() = tiles.sumOf { it.bitmap.allocationByteCount.toLong() }
        val pieces: List<Piece> get() = tiles.map { Piece(it.bitmap, region.left + it.left, region.top + it.top) }
        val islands: List<Island> get() = tiles.map { Island(it.top, it.top + it.bitmap.height) }
    }

    /** What a live request needs besides the frame. */
    private class LiveJob(
        val ticket: Int,
        val brain: LiveBrain,
        val grid: Signature,
        /** The samples of [grid] that never hold still: text there is not worth drawing over. */
        val restless: BooleanArray?,
        /** Earlier translations of this part of the screen, newest first. */
        val known: List<Stored>,
    ) {
        /** Completed when the frame may go to Lens: until then only the preview is made. */
        val go = CompletableDeferred<Unit>()
    }

    /** What a translation depends on besides the picture. */
    private data class LiveKey(
        val target: String,
        val source: String?,
        val ocr: String,
        val layout: ReadingLayout,
        val overlay: OverlayPrefs,
        val onDevice: OnDevicePrefs,
    ) {
        constructor(s: AppSettings) : this(s.targetLanguage, s.sourceLanguage, s.ocrLanguage, s.readingLayout, s.overlay, s.onDevice)
    }

    init {
        scope.launch {
            graph.settings.settings.collect {
                settings = it
                poke()
            }
        }
        capturer.onFrame = { wake.trySend(Unit) }
        // Bottom to top: the translation, the area's grips, the bubble (the menu comes and goes above them).
        layer.attach(screen)
        bubble.attach(screen, bars)
        // Turned on, it may wait for a tap instead of translating at once.
        paused = !startsAtOnce() && mode != ScreenMode.Screen
        applyMode()
        if (!prefs.menuShown) {
            prefs.menuShown = true
            openMenu()
        }
    }

    fun close() {
        capturer.onFrame = null
        scope.cancel()
        menu.hide()
        handles.detach()
        bubble.detach()
        layer.detach()
    }

    /** The display may have rotated or changed size, or its bars may have moved. Safe to call often. */
    fun onDisplayChanged() {
        val size = wm.screenSize()
        if (size != screen) {
            if (!capturer.eventDriven && capturer.continuous) {
                // Screenshots come in the display's true size, whatever anyone says: the next one settles it.
                capturer.hurry(SystemClock.uptimeMillis())
            } else {
                applyDisplay(size)
            }
            return
        }
        val insets = wm.barInsets(density)
        if (insets != bars) {
            bars = insets
            bubble.attach(screen, insets)
        }
    }

    private fun applyDisplay(size: Point) {
        if (size == screen || size.x <= 0 || size.y <= 0) return
        Log.d(TAG, "the display is now ${size.x}x${size.y}")
        screen = size
        bars = wm.barInsets(density)
        memory.clear()
        texts = null
        layer.clear()
        capturer.resize(size.x, size.y, service.resources.configuration.densityDpi)
        layer.attach(size)
        bubble.attach(size, bars)
        applyMode()
    }

    /** The screen went off or was locked ([on] false), or is back. */
    fun onScreen(on: Boolean) {
        asleep = !on
        if (!on && mode == ScreenMode.Screen) once?.cancel()
        poke()
    }

    /** The system is short of memory: keep only the translation on screen. */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        memory.trim(1)
    }

    private fun poke() {
        wake.trySend(Unit)
    }

    // ---- modes ----

    private fun applyMode() {
        once?.cancel()
        stopLive()
        layer.clear()
        busy = false
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
        if (mode == ScreenMode.Screen) {
            // Screenshots cost something each: "this screen" takes one only when tapped.
            capturer.continuous = false
        } else {
            val mine = LiveBrain(
                shell = this,
                memory = memory,
                eventDriven = capturer.eventDriven,
                hideOnTouch = mode == ScreenMode.Live,
                key = LiveKey(settings),
                log = { Log.d(TAG, it) },
            )
            brain = mine
            live = scope.launch { liveLoop(mine) }
        }
        updateBubble()
    }

    private fun stopLive() {
        live?.cancel()
        live = null
        brain = null
        requests.values.forEach { it.cancel() }
        requests.clear()
        gates.clear()
        previews.clear()
    }

    private fun updateBubble() {
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
                    busy = false
                    updateBubble()
                }
                layer.hasContent -> dismiss()
                else -> translateOnce()
            }
            else -> {
                paused = !paused
                failed = false
                updateBubble()
                poke()
            }
        }
    }

    override fun onLongPress() = openMenu()

    private fun openMenu() {
        // A one-shot translation stays up under the menu: "copy" is about what is on screen.
        if (once?.isActive == true) {
            once?.cancel()
            busy = false
        }
        updateBubble()
        menu.show(mode, bars.bottom, canCopy = texts != null, startsAtOnce = startsAtOnce())
        poke()
    }

    override fun onMode(mode: ScreenMode) {
        if (mode == this.mode) return
        this.mode = mode
        prefs.mode = mode
        paused = !startsAtOnce()
        applyMode()
        // The area grips come in their own window, which would cover the menu.
        if (menu.isShown) menu.raise()
    }

    override fun onPrimary() {
        // Set not to translate on its own, "Done" only closes the menu: a tap on the bubble starts.
        if (!startsAtOnce()) {
            updateBubble()
            poke()
            return
        }
        paused = false
        if (mode == ScreenMode.Screen) {
            translateOnce()
        } else {
            updateBubble()
            poke()
        }
    }

    /** Whether turning it on, picking a mode or the menu's main button translates right away. */
    private fun startsAtOnce(): Boolean = graph.settings.current.value.screen.translateOnStart

    override fun onOpenApp() {
        val open = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: return
        runCatching {
            service.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.onFailure { Log.w(TAG, "could not open the app", it) }
    }

    override fun onLanguage(source: Boolean, code: String?) {
        scope.launch {
            graph.settings.update {
                if (source) it.copy(sourceLanguage = code) else it.copy(targetLanguage = code ?: it.targetLanguage)
            }
        }
    }

    override fun onCopy(translation: Boolean) {
        val text = texts?.let { if (translation) it.translation else it.original }?.trim().orEmpty()
        if (text.isEmpty()) return
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Seenly", text)) }
            // Android 13+ shows its own clipboard confirmation.
            .onSuccess { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied) }
            .onFailure { Log.w(TAG, "could not copy", it) }
    }

    override fun onStop() = stop()

    override fun onMenuClosed() {
        dirtyUntil = SystemClock.uptimeMillis() + LiveBrain.SETTLE_OWN_MS
        poke()
    }

    override fun onDragging(dragging: Boolean) {
        this.dragging = dragging
        if (!dragging) capturer.exclude = Rect(lane)
        poke()
    }

    override fun onMoved(footprint: Rect) {
        lane = footprint
        // While it is dragged about, nothing is translated: where it rests is what counts.
        if (!dragging) capturer.exclude = Rect(footprint)
    }

    override fun onOutsideTouch() {
        val mine = brain ?: return
        val now = SystemClock.uptimeMillis()
        mine.onTouch(now)
        // A look at the screen as soon as it can show what the finger did.
        capturer.hurry(now + LiveBrain.TOUCH_HOLD_MS)
        poke()
    }

    override fun onAreaDragging(dragging: Boolean) {
        this.dragging = dragging
        if (!dragging) {
            prefs.saveArea(area, screen.x, screen.y)
            capturer.roi = LayerView.inner(area, density)
        }
        poke()
    }

    override fun onAreaChanged(area: Rect) {
        this.area = area
        layer.area = area
    }

    private fun dismiss() {
        once?.cancel()
        layer.clear()
        busy = false
        updateBubble()
    }

    // ---- "this screen": one shot ----

    private fun translateOnce() {
        once?.cancel()
        onDisplayChanged()
        failed = false
        val ticket = ++run
        once = scope.launch {
            layer.clear()
            busy = true
            updateBubble()
            var shown: Boolean? = null
            try {
                // Whatever of ours was on screen has to be gone from the frames first.
                val clean = max(dirtyUntil, layer.clearedAt + LiveBrain.SETTLE_OWN_MS)
                val current = graph.settings.settings.first()
                // The bubble steps out of the picture as well, so what is under it gets translated too.
                bubble.conceal()
                val grab = try {
                    capturer.refresh(after = max(clean, SystemClock.uptimeMillis() + CONCEAL_MS))
                    capturer.grab(watched = false)
                } finally {
                    bubble.reveal()
                }
                if (grab == null) {
                    toast(R.string.screen_capture_failed)
                    shown = false
                    return@launch
                }
                if (grab.bitmap.width != screen.x || grab.bitmap.height != screen.y) {
                    // The display turned and nobody said: put everything where it belongs, the next tap translates.
                    val size = Point(grab.bitmap.width, grab.bitmap.height)
                    grab.bitmap.recycle()
                    applyDisplay(size)
                    return@launch
                }
                val key = LiveKey(current)
                val known = memory.find(grab.frame.signature, key)?.takeIf { !it.failed }
                val stored = if (known != null) {
                    // The same screen as a moment ago: no need to ask twice.
                    grab.bitmap.recycle()
                    known.payload as? Stored
                } else {
                    translate(grab.bitmap, grab.region, current, null).also {
                        memory.put(grab.frame.signature, key, it.takeIf { made -> made.tiles.isNotEmpty() }, it.bytes)
                    }
                }
                if (stored == null || stored.tiles.isEmpty()) {
                    toast(R.string.screen_no_text)
                    shown = false
                } else {
                    layer.scrim = graph.settings.current.value.screen.resultDim
                    layer.show(stored.pieces, modal = true)
                    texts = stored.texts
                    shown = true
                }
            } catch (e: TimeoutCancellationException) {
                shown = false
                report(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shown = false
                report(e)
            } finally {
                if (ticket == run) {
                    busy = false
                    updateBubble()
                    shown?.let { bubble.buzz(it) }
                }
            }
        }
    }

    // ---- live and area: translate whenever the picture comes to rest ----

    private suspend fun liveLoop(mine: LiveBrain) {
        // The settings as they are, not the defaults they are until they load: a request in the wrong language is wasted.
        settings = graph.settings.settings.first()
        var seen = -1L
        var pace = -1L
        var faults = 0
        while (true) {
            try {
                if (asleep && awake()) asleep = false
                val now = SystemClock.uptimeMillis()
                // Out of the way while the user is busy with us, or is not looking; start over afterwards.
                if (paused || dragging || asleep || menu.isShown) mine.block() else mine.unblock(now)
                mine.settings(LiveKey(settings), now)
                val frame = capturer.frame
                if (frame != null && frame.number != seen) {
                    seen = frame.number
                    if (!capturer.eventDriven && (capturer.width != screen.x || capturer.height != screen.y)) {
                        // A screenshot in another size: the display turned. This restarts the mode, and with it this loop.
                        applyDisplay(Point(capturer.width, capturer.height))
                        return
                    }
                    mine.onFrame(frame, now)
                } else {
                    mine.onTick(now)
                }
                val wanted = mine.cadence(now)
                if (wanted != pace) {
                    pace = wanted
                    if (wanted > 0) capturer.pace(wanted)
                    capturer.continuous = wanted > 0
                }
                faults = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A window the system took from under us, say. One bad turn is not worth the whole service;
                // a run of them is not worth going on with.
                Log.e(TAG, "live loop", e)
                if (++faults >= MAX_FAULTS) {
                    stop()
                    return
                }
            }
            val at = mine.wakeAt
            val wait = if (at == NEVER || faults > 0) IDLE_TICK_MS else (at - SystemClock.uptimeMillis()).coerceIn(MIN_TICK_MS, IDLE_TICK_MS)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    override fun snap(ticket: Int): Signature? {
        val mine = brain ?: return null
        val grab = capturer.grab(watched = true) ?: return null
        paintOutBubble(grab.bitmap, grab.region)
        val region = grab.region
        val job = LiveJob(
            ticket = ticket,
            brain = mine,
            grid = grab.frame.signature,
            restless = mine.volatile,
            known = mine.remembered().filterIsInstance<Stored>().filter { it.region == region },
        )
        val asked = settings
        // Dispatched, never run in place: the brain must know of this request before it hears from it.
        requests[ticket] = scope.launch(Dispatchers.Main) {
            val outcome = try {
                val stored = translate(grab.bitmap, region, asked, job)
                if (stored.tiles.isEmpty()) LiveBrain.Outcome.Empty else LiveBrain.Outcome.Translated(stored, stored.bytes)
            } catch (e: TimeoutCancellationException) {
                LiveBrain.Outcome.Failed(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LiveBrain.Outcome.Failed(e)
            }
            requests.remove(ticket)
            gates.remove(ticket)
            previews.remove(ticket)
            if (brain === mine) {
                mine.onResult(ticket, outcome, SystemClock.uptimeMillis())
                poke()
            }
        }
        gates[ticket] = job.go
        return grab.frame.signature
    }

    override fun send(ticket: Int) {
        gates[ticket]?.complete(Unit)
    }

    override fun cancel(ticket: Int) {
        requests.remove(ticket)?.cancel()
        gates.remove(ticket)
        previews.remove(ticket)
    }

    override fun show(payload: Any, grid: Signature): BooleanArray? {
        val stored = payload as? Stored ?: return null
        val pieces = stored.pieces
        layer.show(pieces, modal = false)
        if (pieces.isEmpty()) return null
        texts = stored.texts
        return paintedBy(pieces, grid)
    }

    override fun preview(ticket: Int, grid: Signature): BooleanArray? {
        val pieces = previews[ticket]?.takeIf { it.isNotEmpty() } ?: return null
        layer.show(pieces, modal = false)
        return paintedBy(pieces, grid)
    }

    override fun hide() = layer.clear()

    override fun status(busy: Boolean, failed: Boolean) {
        this.busy = busy
        this.failed = failed
        updateBubble()
    }

    override fun failed(error: Throwable?, first: Boolean) {
        Log.w(TAG, "live translation failed", error)
        // Once per run of failures: the bubble's red ring says the rest. A toast is on the
        // screen being watched, and one for every scroll while offline would be a loop of its own.
        if (first) toast(R.string.screen_translate_failed, describe(error))
    }

    /** Which samples of [grid] the pieces paint over. */
    private fun paintedBy(pieces: List<Piece>, grid: Signature): BooleanArray {
        val painted = BooleanArray(grid.cells.size)
        val cols = grid.xs.size
        for (piece in pieces) {
            for (r in grid.ys.indices) {
                val y = grid.ys[r]
                if (y < piece.top || y >= piece.bottom) continue
                for (c in 0 until cols) {
                    val x = grid.xs[c]
                    if (x >= piece.left && x < piece.right && piece.paints(x, y)) painted[r * cols + c] = true
                }
            }
        }
        return painted
    }

    // ---- one frame through Lens ----

    /** Paints the bubble out of [frame] (the [region] of the screen), so Lens never reads it. */
    private fun paintOutBubble(frame: Bitmap, region: Rect) {
        val hole = Rect(bubble.bounds)
        if (hole.isEmpty) return
        hole.inset(-4, -4)
        hole.offset(-region.left, -region.top)
        if (!hole.intersect(0, 0, frame.width, frame.height)) return
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
        if (count == 0) return
        val paint = Paint().apply {
            color = (0xFF shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
        }
        Canvas(frame).drawRect(hole, paint)
    }

    /**
     * Sends [frame] to Lens and renders the translation as transparent pieces
     * to lay over it: only the pixels the translation changes are kept, so
     * everything else on screen stays live underneath.
     */
    private suspend fun translate(frame: Bitmap, rect: Rect, settings: AppSettings, live: LiveJob?): Stored =
        // The frame is a whole screen of pixels: give it back as soon as it has served.
        try {
            translateFrame(frame, rect, settings, live)
        } finally {
            frame.recycle()
        }

    private suspend fun translateFrame(frame: Bitmap, rect: Rect, settings: AppSettings, live: LiveJob?): Stored {
        val began = SystemClock.uptimeMillis()
        // In bands, so a tall screen reaches Lens at full resolution rather than shrunk to 1600 px.
        val raster = withContext(Dispatchers.Default) {
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            Raster(frame.width, frame.height, pixels)
        }
        // The columns the bubble can be in are not part of the page.
        val skipFrom = (lane.left - 8 - rect.left).coerceIn(0, raster.width)
        val skipTo = if (lane.isEmpty) skipFrom else (lane.right + 8 - rect.left).coerceIn(skipFrom, raster.width)
        val clearance = (CLEARANCE_DP * density).roundToInt()
        // The page as rows of pixels, kept with its translation: what a later view of it is recognised by.
        val print = withContext(Dispatchers.Default) { RowPrint.of(raster.argb, raster.width, raster.height, skipFrom, skipTo) }
        if (live != null) {
            // What was translated a moment ago and is still on screen, only somewhere else, goes back up at once.
            val pieces = withContext(Dispatchers.Default) {
                Sticky.place(print, live.known.map { Sticky.Seen(it.print, it.islands) }, clearance).map { placed ->
                    val tile = live.known[placed.seen].tiles[placed.island]
                    Piece(tile.bitmap, rect.left + tile.left, rect.top + tile.top + placed.dy)
                }
            }
            if (pieces.isNotEmpty() && brain === live.brain) {
                Log.d(TAG, "live #${live.ticket}: ${pieces.size} pieces kept, ${SystemClock.uptimeMillis() - began} ms in")
                previews[live.ticket] = pieces
                live.brain.onPreviewReady(live.ticket, SystemClock.uptimeMillis())
                poke()
            }
        }
        // The pixels are in hand and what is known of them is on screen; Lens is asked when the brain says so.
        live?.go?.await()
        return withTimeout(REQUEST_TIMEOUT_MS) { scanAndDraw(frame, rect, settings, live, raster, print, began) }
    }

    private suspend fun scanAndDraw(
        frame: Bitmap,
        rect: Rect,
        settings: AppSettings,
        live: LiveJob?,
        raster: Raster,
        print: RowPrint,
        began: Long,
    ): Stored {
        val read = SystemClock.uptimeMillis()
        val scanned = Tiling.scan(raster) { band ->
            val prepared = withContext(Dispatchers.Default) { ImagePreparer.prepare(band) }
            // Lens, or the device where the build and the settings have it stand in.
            graph.scans.scanFrame(prepared, settings)
        }
        val answered = SystemClock.uptimeMillis()
        // The frame is the screen pixel for pixel, so the status bar is its top rows.
        val statusBar = (bars.top - rect.top).toFloat() / frame.height
        // Inside an area, text running into the frame is a piece of a line the frame cuts.
        val cut = rect.width() < screen.x || rect.height() < screen.y
        val result = scanned.copy(
            paragraphs = scanned.paragraphs.filter { p ->
                worthTranslating(p, statusBar) && !(cut && touchesEdge(p, frame.width, frame.height)) &&
                    !(live != null && neverStill(p, rect, frame.width, frame.height, live))
            },
        )
        val texts = Texts(result.translation.orEmpty(), result.text)
        if (result.paragraphs.none { it.translation != null }) return Stored(Rect(rect), emptyList(), print, texts)
        val decoded = decodeOverlay(result, fonts)
        return withContext(Dispatchers.Default) {
            val style = styleFor(result, settings)
            val rendered = renderTranslatedBitmap(
                frame.asImageBitmap(), result, style, decoded,
                // Sizes are real here: the frame is shown pixel for pixel.
                minFontPx = style.minFontSp * ui.resources.displayMetrics.scaledDensity,
            ).asAndroidBitmap()
            // Keep only the pieces the translation changed: a screenful of mostly
            // transparent pixels would cost as much memory as the screen itself.
            val tiles = try {
                cutTiles(rendered, frame)
            } finally {
                rendered.recycle()
            }
            val done = SystemClock.uptimeMillis()
            Log.d(
                TAG,
                "translated ${frame.width}x${frame.height}: read ${read - began} ms, Lens ${answered - read} ms, drawn ${done - answered} ms; " +
                    "${tiles.size} pieces, ${tiles.sumOf { it.bitmap.allocationByteCount } / 1024} KB",
            )
            Stored(Rect(rect), tiles, print, texts)
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

    /**
     * Whether [p] sits on a part of the screen that never holds still (a
     * video, an animated banner): a translation drawn there would be stale
     * before it appeared, and would cover what moves under it.
     */
    private fun neverStill(p: Paragraph, rect: Rect, width: Int, height: Int, live: LiveJob): Boolean {
        val restless = live.restless ?: return false
        val box = p.box ?: return false
        val left = rect.left + (box.centerX - box.width / 2) * width
        val right = rect.left + (box.centerX + box.width / 2) * width
        val top = rect.top + (box.centerY - box.height / 2) * height
        val bottom = rect.top + (box.centerY + box.height / 2) * height
        val grid = live.grid
        val cols = grid.xs.size
        var inside = 0
        var moving = 0
        for (r in grid.ys.indices) {
            val y = grid.ys[r]
            if (y < top || y >= bottom) continue
            for (c in 0 until cols) {
                val x = grid.xs[c]
                if (x < left || x >= right) continue
                inside++
                if (restless[r * cols + c]) moving++
            }
        }
        return moving > 0 && moving * 4 >= inside
    }

    /** The same choice the viewer makes: bubbles reflow, everything else is set in place. */
    private fun styleFor(result: ScanResult, settings: AppSettings): OverlayStyle = settings.overlayStyle(result)

    /**
     * Makes every pixel of [rendered] that equals [frame]'s transparent and
     * cuts what is left into bands, each a bitmap of its own.
     */
    private fun cutTiles(rendered: Bitmap, frame: Bitmap): List<Tile> {
        val width = rendered.width
        val height = rendered.height
        val rows = 64
        val drawn = IntArray(width * rows)
        val original = IntArray(width * rows)
        val minX = IntArray(height)
        val maxX = IntArray(height)
        var y = 0
        while (y < height) {
            val n = min(rows, height - y)
            rendered.getPixels(drawn, 0, width, 0, y, width, n)
            frame.getPixels(original, 0, width, 0, y, width, n)
            for (row in 0 until n) {
                val start = row * width
                var first = Int.MAX_VALUE
                var last = -1
                for (x in 0 until width) {
                    val i = start + x
                    if (drawn[i] == original[i]) {
                        drawn[i] = 0
                    } else {
                        if (first == Int.MAX_VALUE) first = x
                        last = x
                    }
                }
                minX[y + row] = first
                maxX[y + row] = last
            }
            rendered.setPixels(drawn, 0, width, 0, y, width, n)
            y += n
        }
        return bands(minX, maxX, gap = (BAND_GAP_DP * density).roundToInt(), limit = MAX_TILES).map { band ->
            Tile(Bitmap.createBitmap(rendered, band.left, band.top, band.right - band.left, band.bottom - band.top), band.left, band.top)
        }
    }

    private fun report(error: Throwable) {
        Log.w(TAG, "translation failed", error)
        failed = true
        toast(R.string.screen_translate_failed, describe(error))
    }

    private fun describe(error: Throwable?): String = when (error) {
        null -> ""
        is TimeoutCancellationException -> AppLocale.string(service, R.string.screen_timed_out)
        else -> error.message ?: error::class.simpleName.orEmpty()
    }

    private fun toast(@StringRes text: Int, vararg args: Any) =
        Toast.makeText(service, AppLocale.string(service, text, *args), Toast.LENGTH_SHORT).show()

    /** How much the remembered translations may hold: they are bitmaps, a few megabytes a screen. */
    private fun memoryBudget(): Long {
        val manager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return (if (manager.isLowRamDevice) 20L else 56L) * 1024 * 1024
    }

    private companion object {
        const val TAG = "ScreenTranslator"
        /** How close to an area's edge counts as cut by it. */
        const val EDGE_PX = 6f
        /** The bubble takes this long to leave the picture once it is told to. */
        const val CONCEAL_MS = 64L
        /** A request with no answer after this long has failed. */
        const val REQUEST_TIMEOUT_MS = 20_000L
        /** The live loop looks around this often even when nothing calls it. */
        const val IDLE_TICK_MS = 1_000L
        const val MIN_TICK_MS = 8L
        /** This many turns of the live loop failing in a row end the translator. */
        const val MAX_FAULTS = 5
        /** Views remembered with their translations (and by the bytes they hold, see [memoryBudget]). */
        const val MEMORY_VIEWS = 16
        /** Rows of a translation closer together than this stay one piece: the lines of a paragraph. */
        const val BAND_GAP_DP = 12f
        const val MAX_TILES = 48
        /** A kept piece needs this much of the page above and below it to have moved along with it. */
        const val CLEARANCE_DP = 24f
    }
}

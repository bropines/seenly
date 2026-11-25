package io.github.bropines.lens.screen

/*
 * A phone to run the live translator's decisions on, in plain Kotlin: an app
 * that shows a "page" (a grid of brightness samples), our own drawing over it,
 * and a source of frames that behaves like the real ones. A recording
 * delivers a frame 20 ms after anything on screen changes and none otherwise;
 * screenshots are taken at the pace the brain asks for and arrive 100 ms late.
 */

internal const val CELLS = Signature.COLS * Signature.ROWS
internal val SCREEN = Region(0, 0, 400, 720)
private val XS = IntArray(Signature.COLS) { it * 10 + 5 }
private val YS = IntArray(Signature.ROWS) { it * 10 + 5 }

/** The brightness our own drawing has: none of a page's. */
private const val INK: Byte = 250.toByte()

/** A page: samples in six well-separated shades, so two pages differ nearly everywhere. */
internal fun page(seed: Int): ByteArray = ByteArray(CELLS) { i ->
    val mixed = (i * 31 + seed * 7919) * -1640531535
    ((mixed ushr 16) % 6 * 40 + 10).toByte()
}

internal fun signature(cells: ByteArray, skipped: BooleanArray? = null, roi: Region = SCREEN): Signature =
    Signature(roi, cells, XS, YS, skipped)

/** The samples in [rows] x [cols] of the grid. */
internal fun block(rows: IntRange, cols: IntRange): BooleanArray =
    BooleanArray(CELLS) { i -> i / Signature.COLS in rows && i % Signature.COLS in cols }

/** Five shades no page has: a sample repainted in one of them has visibly changed, whatever it was. */
private fun shade(index: Int): Byte = ((index.mod(5) + 1) * 37).toByte()

/**
 * These cells with the samples [where] marks all set to one other shade,
 * chosen by [shade]: two shades differ unless they are a multiple of five apart.
 */
internal fun ByteArray.repainted(where: BooleanArray, shade: Int): ByteArray =
    ByteArray(size) { i -> if (where[i]) shade(shade) else this[i] }

/**
 * These cells with the samples [where] marks set to a pattern of its own for
 * every [variant]: no two variants look alike, and every sample differs
 * between one variant and the next.
 */
internal fun ByteArray.scrambled(where: BooleanArray, variant: Int): ByteArray =
    ByteArray(size) { i ->
        if (!where[i]) return@ByteArray this[i]
        var at = i
        for (step in 0..variant) at += 1 + (((i * 31 + step * 7919) * -1640531535) ushr 20) % 4
        shade(at)
    }

internal class Sim(private val eventDriven: Boolean, hideOnTouch: Boolean = true) : LiveBrain.Shell {
    var now = 100_000L
    val memory = Memory(maxEntries = 8, maxBytes = 1_000_000)
    val log = ArrayList<String>()
    val brain = LiveBrain(this, memory, eventDriven, hideOnTouch, key = "en") { log += "$now: $it" }

    /** What the app under us shows. */
    var page: ByteArray = page(1)

    /** What we have drawn over it: the samples painted, or null for nothing. */
    var drawn: BooleanArray? = null

    /** The views taken to be translated, by ticket, and when. */
    val snaps = ArrayList<Int>()
    val snappedAt = ArrayList<Long>()

    /** The ones actually sent to Lens, and when. */
    val requests = ArrayList<Int>()
    val requestedAt = ArrayList<Long>()
    val cancelled = ArrayList<Int>()
    val shown = ArrayList<Any>()
    var previews = 0
    var hides = 0
    var busy = false
    var failed = false
    val failures = ArrayList<Boolean>()
    val shotsAt = ArrayList<Long>()

    /** What [preview] has to draw for a ticket. */
    val previewOf = HashMap<Int, BooleanArray>()
    private val paints = HashMap<Any, BooleanArray>()

    private var number = 0L
    private var delivered: Frame? = null
    private var onScreen: ByteArray? = null
    private val inTransit = ArrayDeque<Pair<Long, Frame>>()
    private var lastShotAt = Long.MIN_VALUE / 2
    private var hurryAt = 0L

    private fun composite(): ByteArray {
        val cells = page.copyOf()
        drawn?.let { ours -> for (i in cells.indices) if (ours[i]) cells[i] = INK }
        return cells
    }

    fun run(ms: Long) {
        val end = now + ms
        while (now < end) {
            now += STEP_MS
            pump()
        }
    }

    private fun pump() {
        if (eventDriven) {
            val cells = composite()
            if (!cells.contentEquals(onScreen)) {
                onScreen = cells
                inTransit += (now + DELIVERY_MS) to Frame(signature(cells), now + DELIVERY_MS, ++number)
            }
        } else {
            val pace = brain.cadence(now)
            val earliest = lastShotAt + LiveBrain.FAST_MS
            val due = if (hurryAt != 0L) maxOf(hurryAt, earliest) else lastShotAt + pace
            if (pace > 0 && now >= due) {
                lastShotAt = now
                if (now >= hurryAt) hurryAt = 0L
                shotsAt += now
                inTransit += (now + SCREENSHOT_MS) to Frame(signature(composite()), now, ++number)
            }
        }
        while (inTransit.isNotEmpty() && inTransit.first().first <= now) {
            val frame = inTransit.removeFirst().second
            delivered = frame
            brain.onFrame(frame, now)
        }
        if (brain.wakeAt <= now) brain.onTick(now)
    }

    /** A finger comes down somewhere on the screen. */
    fun touch() {
        brain.onTouch(now)
        hurryAt = now + LiveBrain.TOUCH_HOLD_MS
    }

    /** Request [ticket] comes back with a translation that paints over [covers]. */
    fun done(ticket: Int, payload: Any, covers: BooleanArray) {
        paints[payload] = covers
        brain.onResult(ticket, LiveBrain.Outcome.Translated(payload, 1_000), now)
    }

    fun empty(ticket: Int) = brain.onResult(ticket, LiveBrain.Outcome.Empty, now)

    fun fail(ticket: Int) = brain.onResult(ticket, LiveBrain.Outcome.Failed(null), now)

    /** Runs until a request beyond the first [after] has been made (or [limit] ms pass), and returns its ticket. */
    fun awaitRequest(after: Int = requests.size, limit: Long = 5_000): Int {
        val end = now + limit
        while (requests.size <= after && now < end) run(STEP_MS)
        check(requests.size > after) { "no request within $limit ms\n" + log.takeLast(12).joinToString("\n") }
        return requests.last()
    }

    override fun snap(ticket: Int): Signature? {
        val frame = delivered ?: return null
        snaps += ticket
        snappedAt += now
        return frame.signature
    }

    override fun send(ticket: Int) {
        requests += ticket
        requestedAt += now
    }

    override fun cancel(ticket: Int) {
        cancelled += ticket
    }

    override fun show(payload: Any, grid: Signature): BooleanArray? {
        shown += payload
        drawn = paints[payload]
        return drawn
    }

    override fun preview(ticket: Int, grid: Signature): BooleanArray? {
        val pieces = previewOf[ticket] ?: return null
        previews++
        drawn = pieces
        return pieces
    }

    override fun hide() {
        hides++
        drawn = null
    }

    override fun status(busy: Boolean, failed: Boolean) {
        this.busy = busy
        this.failed = failed
    }

    override fun failed(error: Throwable?, first: Boolean) {
        failures += first
    }

    companion object {
        const val STEP_MS = 4L
        /** A recording: a change on screen reaches us this much later. */
        const val DELIVERY_MS = 20L
        /** A screenshot arrives this long after it was asked for. */
        const val SCREENSHOT_MS = 100L
    }
}

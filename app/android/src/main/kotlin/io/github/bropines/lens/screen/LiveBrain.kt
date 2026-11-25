package io.github.bropines.lens.screen

import kotlin.math.max
import kotlin.math.min

/*
 * Everything that decides when the live translation is asked for, shown and
 * taken away. Plain Kotlin, clocks passed in, so the whole of it runs in unit
 * tests: the Android side (ScreenTranslator) only carries out what is decided.
 */

internal const val NEVER = Long.MAX_VALUE

/** This many changed samples (of 2880) mean the view is a different one. */
internal const val CHANGED_CELLS = 12

/** Fewer changed samples than this between two frames is a clock tick or a cursor, not motion. */
internal const val MOTION_CELLS = 4

/**
 * Watches the frames go by: when the picture last moved, and which parts of it
 * never stop moving.
 *
 * A screen recording delivers a frame for every change on screen and nothing
 * while it is still ([eventDriven]), so no frame for a while means rest.
 * Screenshots come at a fixed pace of about three a second whatever the screen
 * does: there, rest is two frames in a row showing the same picture. Taking the
 * first frame after a movement for rest would send a request for every frame
 * of a scroll.
 */
internal class Motion(private val eventDriven: Boolean) {
    private var previous: Frame? = null
    private var changedAt = LongArray(0)
    private var streakFrom = LongArray(0)
    private var streak = IntArray(0)
    private var changed = BooleanArray(0)

    /** `takenAt` of the last frame that differed from the one before it. */
    var lastMotionAt = 0L
        private set

    /** How many frames in a row showed nothing moving. */
    var quiet = 0
        private set

    /**
     * The samples that keep changing while the rest holds still: a spinner, a
     * GIF, a video in a corner. They are left out of every comparison, so they
     * neither keep the translation from coming nor take it away. Null when
     * there are none, or so many that it is the screen itself that is moving.
     */
    var volatile: BooleanArray? = null
        private set

    /**
     * A recording: when the first of the [volatile] samples stops being so
     * unless it changes again, or [NEVER]. A recording sends no frame while
     * nothing changes, so somebody has to come back then and [expire] it.
     */
    var volatileUntil = NEVER
        private set

    /** Restarts the stillness clock, as if the picture had just moved. */
    fun restart(now: Long) {
        lastMotionAt = max(lastMotionAt, now)
        quiet = 0
    }

    fun still(now: Long): Boolean = if (eventDriven) now - lastMotionAt >= STILL_MS else quiet >= 1

    /**
     * A recording: lets the samples that have stopped changing count again.
     * Without this, what loads in place of a loading animation (which is
     * volatile) would never be seen: its one frame is the animation's last.
     */
    fun expire(now: Long) {
        val mask = volatile ?: return
        if (!eventDriven || now < volatileUntil) return
        var kept = 0
        var next = NEVER
        val rest = BooleanArray(mask.size)
        for (i in mask.indices) {
            if (mask[i] && now - changedAt[i] <= ACTIVE_MS) {
                rest[i] = true
                kept++
                next = min(next, changedAt[i] + ACTIVE_MS + 1)
            }
        }
        volatile = rest.takeIf { kept > 0 }
        volatileUntil = next
    }

    /**
     * Takes in the next frame. [unsure] are the samples our own drawing came
     * to or left at some time before [sureAt]: a change there between frames
     * that old is ours, not the screen's.
     */
    fun feed(frame: Frame, unsure: BooleanArray? = null, sureAt: Long = 0L) {
        val before = previous
        previous = frame
        val now = frame.signature
        val size = now.cells.size
        if (before == null || !now.comparable(before.signature) || changedAt.size != size) {
            changedAt = LongArray(size)
            streakFrom = LongArray(size)
            streak = IntArray(size)
            changed = BooleanArray(size)
            volatile = null
            volatileUntil = NEVER
            lastMotionAt = max(lastMotionAt, frame.takenAt)
            quiet = 0
            return
        }
        val at = frame.takenAt
        // "Keeps changing" means changing again within this long: a few frames of an animation.
        val window = if (eventDriven) ACTIVE_MS else ((at - before.takenAt) * 23 / 10).coerceIn(750L, 4_000L)
        val ours = if (unsure != null && before.takenAt < sureAt) unsure else null
        for (i in 0 until size) {
            val moved = now.usable(i, before.signature) && !(ours != null && ours[i]) && now.differs(i, before.signature)
            changed[i] = moved
            if (moved) {
                if (at - changedAt[i] > window) {
                    streakFrom[i] = at
                    streak[i] = 1
                } else {
                    streak[i]++
                }
                changedAt[i] = at
            }
        }
        var hot = 0
        var until = NEVER
        val mask = BooleanArray(size)
        for (i in 0 until size) {
            if (streak[i] >= VOLATILE_STREAK && at - changedAt[i] <= window && (!eventDriven || at - streakFrom[i] >= VOLATILE_MS)) {
                mask[i] = true
                hot++
                until = min(until, changedAt[i] + window + 1)
            }
        }
        // A third of the screen "animating" is not an animation: the screen is scrolling.
        val honoured = mask.takeIf { hot > 0 && hot * 100 <= size * MAX_VOLATILE_PERCENT }
        volatile = honoured
        volatileUntil = if (honoured != null) until else NEVER
        var moving = 0
        for (i in 0 until size) if (changed[i] && !(honoured != null && honoured[i])) moving++
        if (moving >= MOTION_CELLS) {
            lastMotionAt = at
            quiet = 0
        } else {
            quiet++
        }
    }

    companion object {
        /** How long a recorded picture must hold still before it is translated. */
        const val STILL_MS = 200L
        /** A recording: a sample changing again within this long is still animating. */
        const val ACTIVE_MS = 400L
        /** A recording: animating for this long makes a sample volatile, so an ordinary transition does not. */
        const val VOLATILE_MS = 600L
        /** Changes in a row before a sample is volatile. */
        const val VOLATILE_STREAK = 3
        const val MAX_VOLATILE_PERCENT = 35
    }
}

/**
 * How often Lens may be asked. Three things are kept apart, because the old
 * single back-off punished the user for scrolling:
 *
 * - a budget ([BURST] requests at once, one more every [REFILL_MS]), which a
 *   person paging through something never exhausts;
 * - a growing gap between requests nobody asked for (a corner of the screen
 *   that changes by itself, with no touch near it in time), so a carousel or a
 *   ticker cannot keep the translator busy: this is what stops loops;
 * - a growing gap after failures, so being offline is not a request per scroll.
 */
internal class Pacer {
    private var tokens = BURST.toDouble()
    private var filledAt = 0L
    private var lastAt = NEVER
    private var unattended = 0
    private var failures = 0

    /** How long to wait before the next request may start; 0 is now. */
    fun delay(now: Long, attended: Boolean): Long {
        if (lastAt == NEVER) return 0
        refill(now)
        var at = lastAt + MIN_GAP_MS
        if (tokens < 1.0) at = max(at, now + ((1.0 - tokens) * REFILL_MS).toLong())
        if (!attended && unattended > 0) at = max(at, lastAt + grown(UNATTENDED_BASE_MS, unattended, UNATTENDED_MAX_MS))
        if (failures > 0) at = max(at, lastAt + grown(FAILED_BASE_MS, failures, FAILED_MAX_MS))
        return max(0L, at - now)
    }

    fun started(now: Long, attended: Boolean) {
        refill(now)
        tokens = max(0.0, tokens - 1.0)
        lastAt = now
        unattended = if (attended) 0 else unattended + 1
    }

    fun succeeded() {
        failures = 0
    }

    /** Returns whether this failure is the first after a success: the one worth telling the user about. */
    fun failed(): Boolean = ++failures == 1

    private fun refill(now: Long) {
        if (filledAt != 0L) tokens = min(BURST.toDouble(), tokens + (now - filledAt).toDouble() / REFILL_MS)
        filledAt = now
    }

    private fun grown(base: Long, times: Int, limit: Long): Long = min(limit, base shl min(times - 1, 16))

    companion object {
        const val BURST = 5
        const val REFILL_MS = 1_500L
        /** No two requests closer than this, however restless the screen. */
        const val MIN_GAP_MS = 300L
        const val UNATTENDED_BASE_MS = 1_500L
        const val UNATTENDED_MAX_MS = 24_000L
        const val FAILED_BASE_MS = 2_000L
        const val FAILED_MAX_MS = 30_000L
    }
}

/**
 * The views translated lately and what came of them, newest last: a view seen
 * a moment ago is shown again from here, never sent to Lens twice. A view
 * whose request failed is remembered too, so it is not asked for again the
 * instant it fails. Bounded by count and by the bytes the translations hold.
 */
internal class Memory(private val maxEntries: Int, private val maxBytes: Long) {
    class Entry internal constructor(val signature: Signature, val key: Any) {
        /** What to draw; null when there was nothing to translate, or the request failed. */
        var payload: Any? = null
            internal set
        var bytes = 0L
            internal set
        var failed = false
            internal set
        internal var attempts = 0
        internal var failedAt = 0L

        /** When a failed view is worth another try by itself, or [NEVER]. */
        val retryAt: Long
            get() = if (failed && attempts in 1..RETRY_AFTER_MS.size) failedAt + RETRY_AFTER_MS[attempts - 1] else NEVER

        fun retryDue(now: Long): Boolean = retryAt != NEVER && now >= retryAt
    }

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size
    val bytes: Long get() = entries.sumOf { it.bytes }

    /** The newest entry for a view that looks like [signature], leaving [ignore]d samples out. */
    fun find(signature: Signature, key: Any, ignore: BooleanArray? = null): Entry? {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.key == key && signature.diff(entry.signature, ignore) < CHANGED_CELLS) return entry
        }
        return null
    }

    /** Remembers a translation ([payload] null: nothing to translate there), instead of whatever was known of that view. */
    fun put(signature: Signature, key: Any, payload: Any?, bytes: Long): Entry {
        entries.removeAll { it.key == key && it.signature.diff(signature) < CHANGED_CELLS }
        val entry = Entry(signature, key)
        entry.payload = payload
        entry.bytes = if (payload == null) 0 else bytes
        entries.addLast(entry)
        var total = this.bytes
        while (entries.size > 1 && (entries.size > maxEntries || total > maxBytes)) total -= entries.removeFirst().bytes
        return entry
    }

    /** Remembers that the request for this view failed at [now], counting the tries. */
    fun fail(signature: Signature, key: Any, now: Long): Entry {
        val known = entries.lastOrNull { it.key == key && it.failed && it.signature.diff(signature) < CHANGED_CELLS }
        val entry = put(signature, key, null, 0)
        entry.failed = true
        entry.attempts = (known?.attempts ?: 0) + 1
        entry.failedAt = now
        return entry
    }

    /** What is remembered for [key], newest first: the translations a new view may be pieced together from. */
    fun payloads(key: Any): List<Any> = entries.asReversed().filter { it.key == key }.mapNotNull { it.payload }

    /** Lets go of all but the newest [keep] entries: the system is short of memory. */
    fun trim(keep: Int) {
        while (entries.size > keep) entries.removeFirst()
    }

    fun clear() = entries.clear()

    companion object {
        /** A failed view is tried again by itself this long after each failure, then left alone. */
        val RETRY_AFTER_MS = longArrayOf(3_000L, 10_000L)
    }
}

/**
 * The live and area modes' decisions. It is fed frames, touches and finished
 * requests, and tells its [Shell] what to do: ask Lens, draw, hide.
 *
 * A view is "committed to" once it is being translated or shown ([base] is
 * its clean picture). While committed, every frame is compared with it; when
 * the picture leaves it, the translation is hidden and the watch for the next
 * resting view begins. That watch ends in one of three ways: the view is one
 * remembered (shown at once), the request for it is already under way (the
 * user only put a finger down), or it is new and has come to rest.
 *
 * A new view is first only "snapped": its pixels are taken, and whatever of
 * it was translated before goes back up at once (see Sticky). The pixels are
 * sent to Lens when the [Pacer] allows, which is usually the same instant;
 * when it is not, the screen is at least not left bare while waiting.
 *
 * Our own drawing is in the frames too. The samples it paints over are
 * compared with a frame taken after it went up ([reference]), never with the
 * clean view; and for a moment after it comes or goes, those samples are not
 * compared at all ([unsure], [sureAt]).
 */
internal class LiveBrain(
    private val shell: Shell,
    private val memory: Memory,
    /** A screen recording (a frame per change, none at rest) rather than paced screenshots. */
    private val eventDriven: Boolean,
    /** Whole-screen mode: a finger on the screen hides the translation at once. An area keeps it. */
    private val hideOnTouch: Boolean,
    /** What a translation depends on besides the picture: the languages and how it is drawn. */
    private var key: Any,
    private val log: (String) -> Unit = {},
) {
    interface Shell {
        /**
         * Takes the pixels of the latest frame for request [ticket] and holds
         * on to them; if anything of that view was translated before, readies
         * it for [preview] and says so through [onPreviewReady]. Returns that
         * frame's signature, or null if there is no frame to take.
         */
        fun snap(ticket: Int): Signature?

        /**
         * Sends what [snap] took for [ticket] to Lens. The work ends in
         * [onResult] with the same ticket, unless cancelled first.
         */
        fun send(ticket: Int)

        /** Lets go of everything [ticket] holds, sent or not. */
        fun cancel(ticket: Int)

        /** Draws a remembered translation. Returns which samples of [grid] it paints over. */
        fun show(payload: Any, grid: Signature): BooleanArray?

        /**
         * Draws what is known of the view [ticket] is of, pieced together from
         * earlier translations, if there is anything. Returns which samples of
         * [grid] it paints over, or null for nothing.
         */
        fun preview(ticket: Int, grid: Signature): BooleanArray?

        fun hide()

        /** For the bubble: a request is in flight, or the view on screen could not be translated. */
        fun status(busy: Boolean, failed: Boolean)

        /** A request failed. [first] is false for the second and later failures in a row. */
        fun failed(error: Throwable?, first: Boolean)
    }

    sealed interface Outcome {
        /** [payload] is what [Shell.show] will be handed; [bytes] is what it holds in memory. */
        class Translated(val payload: Any, val bytes: Long) : Outcome

        /** Nothing to translate in that view. */
        data object Empty : Outcome

        class Failed(val error: Throwable?) : Outcome
    }

    private class Pending(val ticket: Int, val base: Signature, val snappedAt: Long, val attended: Boolean) {
        var sentAt = NEVER
        val sent: Boolean get() = sentAt != NEVER
    }

    private val motion = Motion(eventDriven)
    private val pacer = Pacer()
    private var latest: Frame? = null
    private var blocked = false

    /** The clean picture of the view being translated or shown; null while watching for one. */
    private var base: Signature? = null
    private var entry: Memory.Entry? = null
    private var committedAt = 0L
    private var request: Pending? = null
    private var tickets = 0

    /** The view left last, to tell a corner changing from a new page. */
    private var lastBase: Signature? = null

    /** The samples our drawing paints over now; null with nothing drawn. */
    private var covered: BooleanArray? = null
    /** The samples our drawing came to or left at [changedAt]. */
    private var unsure: BooleanArray? = null
    /** Until a frame this fresh, nothing at all can be trusted: the menu was up. */
    private var unsureAll = false
    private var changedAt = 0L
    private var sureAt = 0L
    /** A frame with the drawing as it is now on it. */
    private var reference: Signature? = null

    private var touchAt = LONG_AGO
    private var touchSeen = false

    /** When [onTick] is wanted next, or [NEVER] to wait for a frame. */
    var wakeAt = NEVER
        private set

    /** The samples left out as never still, on the grid of the latest frame; see [Motion.volatile]. */
    val volatile: BooleanArray? get() = motion.volatile

    /** What is remembered for the current settings, newest first. */
    fun remembered(): List<Any> = memory.payloads(key)

    fun onFrame(frame: Frame, now: Long) {
        motion.feed(frame, unsure, sureAt)
        latest = frame
        evaluate(now)
    }

    fun onTick(now: Long) = evaluate(now)

    /** A finger came down somewhere on the screen (not on anything of ours). */
    fun onTouch(now: Long) {
        touchSeen = true
        touchAt = now
        if (blocked) return
        if (hideOnTouch && base != null) {
            // Whatever the finger is about to do, the translation must not float over it. The
            // request, if one is under way, carries on: the view may well stay as it is.
            log("touch: translation out of the way")
            uncommit(now)
        }
        evaluate(now)
    }

    /** The translation of request [ticket] is ready (or failed). */
    fun onResult(ticket: Int, outcome: Outcome, now: Long) {
        val pending = request?.takeIf { it.ticket == ticket } ?: return
        request = null
        val current = base === pending.base
        when (outcome) {
            is Outcome.Translated -> {
                pacer.succeeded()
                val stored = memory.put(pending.base, key, outcome.payload, outcome.bytes)
                // Hidden under a finger meanwhile: the next resting frame finds it in memory.
                if (current) {
                    entry = stored
                    adopt(shell.show(outcome.payload, pending.base), now)
                }
            }
            Outcome.Empty -> {
                pacer.succeeded()
                val stored = memory.put(pending.base, key, null, 0)
                if (current) {
                    entry = stored
                    if (covered != null) shell.hide()
                    adopt(null, now)
                }
            }
            is Outcome.Failed -> {
                val first = pacer.failed()
                val stored = memory.fail(pending.base, key, now)
                if (current) entry = stored
                shell.failed(outcome.error, first)
            }
        }
        log("request #$ticket answered ${now - pending.sentAt} ms after it was sent: ${outcome::class.simpleName}")
        shell.status(busy = false, failed = base != null && entry?.failed == true)
        evaluate(now)
    }

    /** The shell has pieced a preview together for [ticket]: draw it if that view is still up. */
    fun onPreviewReady(ticket: Int, now: Long) {
        val pending = request?.takeIf { it.ticket == ticket } ?: return
        if (blocked || base !== pending.base || covered != null) return
        adopt(shell.preview(ticket, pending.base), now)
        evaluate(now)
    }

    /** The user is busy with the bubble, its menu or the area, or the screen is off: out of the way. */
    fun block() {
        if (blocked) return
        blocked = true
        request?.let { shell.cancel(it.ticket) }
        request = null
        if (covered != null) shell.hide()
        covered = null
        unsure = null
        reference = null
        base = null
        entry = null
        wakeAt = NEVER
        shell.status(busy = false, failed = false)
    }

    fun unblock(now: Long) {
        if (!blocked) return
        blocked = false
        // Whatever of ours was on screen (the menu, the area being dragged) is still in the frames.
        unsureAll = true
        changedAt = now
        sureAt = now + SETTLE_OWN_MS
        motion.restart(now)
        evaluate(now)
    }

    /** The languages or the drawing settings changed: what is shown was made for the old ones. */
    fun settings(key: Any, now: Long) {
        if (key == this.key) return
        this.key = key
        if (blocked) return
        request?.let { shell.cancel(it.ticket) }
        request = null
        if (base != null) uncommit(now)
        lastBase = null
        evaluate(now)
    }

    /**
     * How often a source of paced screenshots should take one, in milliseconds;
     * 0 for not at all. A view that has been still for a while is looked at
     * less often: a touch (seen at once, see [onTouch]) brings the pace back.
     * Until a touch has ever been seen, the slow paces are not trusted.
     */
    fun cadence(now: Long): Long {
        if (blocked) return 0
        if (base == null || request != null || (covered != null && reference == null)) return FAST_MS
        // Something on screen is animating: looked at less often, it could pass for still.
        if (motion.volatile != null) return FAST_MS
        val calm = now - max(max(motion.lastMotionAt, touchAt), committedAt)
        return when {
            calm < 3_000 -> FAST_MS
            !touchSeen -> if (calm < 6_000) FAST_MS else 500
            calm < 20_000 -> 600
            calm < 90_000 -> 1_000
            else -> 1_500
        }
    }

    private fun evaluate(now: Long) {
        wakeAt = NEVER
        if (blocked) return
        motion.expire(now)
        decide(now)
        // A recording is silent while nothing changes: come back when what was animating has had time to stop.
        if (eventDriven && motion.volatileUntil != NEVER) wake(max(motion.volatileUntil, now + 1))
    }

    private fun decide(now: Long) {
        val frame = latest ?: return
        val sig = frame.signature
        if (asOf(frame, now) >= sureAt) unsureAll = false
        if (unsureAll) {
            wakeWhenAsOf(frame, sureAt)
            return
        }
        // The request under way was made from a view that is gone: its answer would land on the wrong picture.
        request?.let { pending ->
            if (left(pending.base, frame, now)) {
                log("the view moved on ${now - pending.snappedAt} ms into request #${pending.ticket}: dropped")
                shell.cancel(pending.ticket)
                request = null
                if (base !== pending.base) shell.status(busy = false, failed = false)
            }
        }
        val shown = base
        if (shown != null) {
            if (left(shown, frame, now)) {
                log("the view changed: translation hidden")
                uncommit(now)
            } else {
                if (covered != null && reference == null && frame.takenAt >= sureAt) reference = sig
                if (asOf(frame, now) < sureAt) wakeWhenAsOf(frame, sureAt)
                val pending = request
                val known = entry
                if (pending != null) {
                    if (!pending.sent) trySend(pending, now)
                } else if (known != null && known.failed) {
                    if (known.retryDue(now)) {
                        // Once more, and from a clean picture: what is drawn now must not be in it.
                        uncommit(now)
                    } else {
                        wake(known.retryAt)
                    }
                }
            }
            if (base != null) return
        }
        // Watching for a view to commit to.
        if (hideOnTouch && asOf(frame, now) < touchAt + TOUCH_HOLD_MS) {
            // A finger just came down: a scroll shows in the picture a moment later.
            wakeWhenAsOf(frame, touchAt + TOUCH_HOLD_MS)
            return
        }
        request?.let { pending ->
            // Hidden under a finger, and the view is still the one being translated: carry on with it.
            base = pending.base
            entry = null
            committedAt = now
            adopt(shell.preview(pending.ticket, pending.base), now)
            shell.status(busy = pending.sent, failed = false)
            if (!pending.sent) trySend(pending, now)
            return
        }
        // A picture that matches a remembered one, sample for sample, needs no further proof that
        // nothing of ours is left in it: it would not match.
        val hit = memory.find(sig, key, motion.volatile)
        if (hit != null && !hit.retryDue(now)) {
            if (eventDriven && now - motion.lastMotionAt < QUICK_STILL_MS) {
                wake(motion.lastMotionAt + QUICK_STILL_MS)
                return
            }
            base = sig
            entry = hit
            committedAt = now
            hit.payload?.let { adopt(shell.show(it, sig), now) }
            if (hit.failed) wake(hit.retryAt)
            shell.status(busy = false, failed = hit.failed)
            log(if (hit.failed) "a view that failed before: waiting to try again" else "a view seen before: shown from memory")
            return
        }
        // A new view: its pixels go to Lens, so first a picture that has nothing of ours in it.
        if (asOf(frame, now) < sureAt) {
            wakeWhenAsOf(frame, sureAt)
            return
        }
        if (!motion.still(now)) {
            if (eventDriven) wake(motion.lastMotionAt + Motion.STILL_MS)
            return
        }
        val ticket = ++tickets
        val taken = shell.snap(ticket)
        if (taken == null) {
            wake(now + 500)
            return
        }
        val pending = Pending(ticket, taken, now, attended(sig, now))
        base = taken
        entry = null
        committedAt = now
        request = pending
        shell.status(busy = false, failed = false)
        log("view #$ticket taken ${now - motion.lastMotionAt} ms after the picture came to rest")
        trySend(pending, now)
    }

    /** Sends a snapped view to Lens if the pacer allows it now; else comes back when it will. */
    private fun trySend(pending: Pending, now: Long) {
        val wait = pacer.delay(now, pending.attended)
        if (wait > 0) {
            wake(now + wait)
            return
        }
        pending.sentAt = now
        pacer.started(now, pending.attended)
        shell.send(pending.ticket)
        shell.status(busy = true, failed = false)
        val held = now - pending.snappedAt
        log("request #${pending.ticket} sent" + (if (held > 0) ", held back $held ms" else "") + if (pending.attended) "" else " (nobody asked)")
    }

    /**
     * The time the latest frame can be taken to describe the screen at. A
     * screenshot describes the moment it was taken. A recording delivers every
     * change, so its latest frame is the screen as it is now, give or take the
     * delivery; unless no frame has come since we last changed something
     * ourselves, in which case that frame may still be on its way.
     */
    private fun asOf(frame: Frame, now: Long): Long =
        if (!eventDriven) frame.takenAt else max(frame.takenAt, now - if (frame.takenAt >= changedAt) DELIVERY_MS else LATE_MS)

    /** Comes back when the picture describes the screen at [time]: a recording sends nothing if nothing changes. */
    private fun wakeWhenAsOf(frame: Frame, time: Long) {
        if (eventDriven) wake(time + if (frame.takenAt >= changedAt) DELIVERY_MS else LATE_MS)
    }

    private fun wake(at: Long) {
        wakeAt = min(wakeAt, at)
    }

    /** Whether the picture is no longer the view [target] was taken from. */
    private fun left(target: Signature, frame: Frame, now: Long): Boolean {
        val sig = frame.signature
        if (!sig.comparable(target)) return true
        val sure = asOf(frame, now) >= sureAt
        val never = motion.volatile
        val drawn = covered
        val doubt = unsure
        val settled = reference
        var count = 0
        for (i in sig.cells.indices) {
            if (never != null && never[i]) continue
            if (!sig.usable(i, target)) continue
            val over = drawn != null && drawn[i]
            if (!sure && (over || (doubt != null && doubt[i]))) continue
            if (over) {
                // Under our drawing: only a frame that already had the drawing on it tells.
                if (settled != null && sig.usable(i, settled) && sig.differs(i, settled)) count++
            } else if (sig.differs(i, target)) {
                count++
            }
        }
        return count >= CHANGED_CELLS
    }

    /** Our drawing now paints over [next] (null: nothing drawn). */
    private fun adopt(next: BooleanArray?, now: Long) {
        val before = covered
        if (before == null && next == null) return
        val pending = unsure.takeIf { now < sureAt }
        val size = (next ?: before)!!.size
        unsure = BooleanArray(size) { i ->
            (before != null && before[i]) || (next != null && next[i]) || (pending != null && i < pending.size && pending[i])
        }
        covered = next
        reference = null
        changedAt = now
        sureAt = max(sureAt, now + SETTLE_OWN_MS)
    }

    private fun uncommit(now: Long) {
        lastBase = base ?: lastBase
        base = null
        entry = null
        if (covered != null) shell.hide()
        adopt(null, now)
        shell.status(busy = request?.sent == true, failed = false)
    }

    /**
     * Whether somebody is likely waiting for this view's translation: a touch
     * a moment ago, or a picture that is a different page altogether (turned
     * with a volume key, say, which is no touch). A corner of the same page
     * changing by itself is not.
     */
    private fun attended(sig: Signature, now: Long): Boolean {
        if (touchSeen && now - touchAt <= ATTENTION_MS) return true
        val before = lastBase ?: return true
        if (!sig.comparable(before)) return true
        return sig.diff(before, motion.volatile) * 100 >= sig.cells.size * NEW_PAGE_PERCENT
    }

    companion object {
        /** A recording: a remembered view is shown this soon after the picture stops. */
        const val QUICK_STILL_MS = 80L
        /** How long our own windows take to appear in (or leave) the captured frames. */
        const val SETTLE_OWN_MS = 100L
        /** A recording: a change on screen reaches us within this. */
        const val DELIVERY_MS = 64L
        /** A recording: with no frame at all since our own change, wait this long before trusting the old one. */
        const val LATE_MS = 400L
        /** After a finger comes down, how long before the picture can show whether it is scrolling. */
        const val TOUCH_HOLD_MS = 250L
        /** A request this soon after a touch was asked for by it. */
        const val ATTENTION_MS = 5_000L
        /** This much of the picture differing from the last view makes it a new page. */
        const val NEW_PAGE_PERCENT = 20
        /** The system's minimum gap between two accessibility screenshots, plus a little. */
        const val FAST_MS = 340L
        private const val LONG_AGO = Long.MIN_VALUE / 4
    }
}

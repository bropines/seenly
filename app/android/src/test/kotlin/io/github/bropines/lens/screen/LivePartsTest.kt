package io.github.bropines.lens.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts the live translator's decisions are made of: signatures, motion, pacing, memory. */
class LivePartsTest {
    // ---- signatures ----

    @Test
    fun `a signature counts the samples that differ visibly`() {
        val a = page(1)
        val b = a.copyOf()
        b[0] = (b[0] + 10).toByte()   // within tolerance
        b[1] = (b[1] + 11).toByte()   // just past it
        b[2] = (b[2] + 80).toByte()
        assertEquals(0, signature(a).diff(signature(a)))
        assertEquals(2, signature(a).diff(signature(b)))
        assertEquals(1, signature(a).diff(signature(b), ignore = BooleanArray(CELLS) { it == 1 }))
    }

    @Test
    fun `samples either signature skipped never count`() {
        val a = page(1)
        val b = page(2)
        val everything = signature(a).diff(signature(b))
        assertTrue(everything > CELLS / 2)
        val lane = block(rows = 0..71, cols = 34..39)
        val hidden = (0 until CELLS).count { lane[it] && signature(a).differs(it, signature(b)) }
        assertTrue(hidden > 0)
        assertEquals(everything - hidden, signature(a, skipped = lane).diff(signature(b)))
        assertEquals(everything - hidden, signature(a).diff(signature(b, skipped = lane)))
    }

    @Test
    fun `signatures of different regions are not comparable`() {
        val a = signature(page(1))
        val b = signature(page(1), roi = Region(0, 100, 400, 500))
        assertFalse(a.comparable(b))
        assertEquals(Int.MAX_VALUE, a.diff(b))
    }

    @Test
    fun `sampling reads the middle of each cell and marks what the bubble covers`() {
        val seen = ArrayList<Pair<Int, Int>>()
        val sig = sampleSignature(400, 720, roi = null, skip = Region(350, 0, 400, 100)) { x, y ->
            seen += x to y
            (x + y) % 256
        }
        assertEquals(Region(0, 0, 400, 720), sig.roi)
        assertEquals(5, sig.xs[0])
        assertEquals(395, sig.xs[39])
        assertEquals(5, sig.ys[0])
        assertEquals(715, sig.ys[71])
        val skipped = sig.skipped
        assertNotNull(skipped)
        // Five columns (350..399) by ten rows (0..99) of samples are under the bubble.
        assertEquals(50, skipped!!.count { it })
        assertEquals(CELLS - 50, seen.size)
        assertTrue(seen.none { (x, y) -> x >= 350 && y < 100 })
        assertEquals(((5 + 5) % 256).toByte(), sig.cells[0])
    }

    @Test
    fun `sampling keeps to the watched region, or to the frame when that lies outside it`() {
        val inside = sampleSignature(400, 720, roi = Region(100, 200, 300, 560), skip = null) { _, _ -> 0 }
        assertEquals(Region(100, 200, 300, 560), inside.roi)
        assertEquals(102, inside.xs[0])
        assertEquals(202, inside.ys[0])
        assertNull(inside.skipped)
        val outside = sampleSignature(400, 720, roi = Region(500, 800, 600, 900), skip = null) { _, _ -> 0 }
        assertEquals(Region(0, 0, 400, 720), outside.roi)
    }

    // ---- motion ----

    private fun frame(cells: ByteArray, at: Long, number: Long) = Frame(signature(cells), at, number)

    @Test
    fun `screenshots - rest is two frames that agree`() {
        val motion = Motion(eventDriven = false)
        motion.feed(frame(page(1), 1_000, 1))
        assertFalse(motion.still(1_100))
        motion.feed(frame(page(2), 1_340, 2))
        assertFalse("however long ago that frame was taken", motion.still(9_000))
        motion.feed(frame(page(2), 1_680, 3))
        assertTrue(motion.still(1_700))
        assertEquals(1_340, motion.lastMotionAt)
    }

    @Test
    fun `a recording - rest is no frame for a fifth of a second`() {
        val motion = Motion(eventDriven = true)
        motion.feed(frame(page(1), 1_000, 1))
        motion.feed(frame(page(2), 1_016, 2))
        assertFalse(motion.still(1_100))
        assertTrue(motion.still(1_016 + Motion.STILL_MS))
        motion.restart(2_000)
        assertFalse(motion.still(2_100))
        assertTrue(motion.still(2_200))
    }

    @Test
    fun `three changed samples are a clock ticking, four are motion`() {
        val motion = Motion(eventDriven = false)
        val base = page(1)
        motion.feed(frame(base, 1_000, 1))
        motion.feed(frame(base, 1_340, 2))
        motion.feed(frame(base.repainted(block(0..0, 0..2), 1), 1_680, 3))
        assertTrue(motion.still(1_700))
        motion.feed(frame(base.repainted(block(0..0, 0..3), 2), 2_020, 4))
        assertFalse(motion.still(2_100))
    }

    @Test
    fun `samples that change frame after frame become volatile, and stop being so when they rest`() {
        for (recording in listOf(false, true)) {
            val motion = Motion(eventDriven = recording)
            val base = page(1)
            val gif = block(rows = 10..19, cols = 0..9)
            val step = if (recording) 100L else 340L
            var at = 1_000L
            var number = 0L
            motion.feed(frame(base, at, ++number))
            // The first frames of the animation are motion like any other.
            repeat(2) {
                at += step
                motion.feed(frame(base.repainted(gif, it), at, ++number))
            }
            assertNull(motion.volatile)
            repeat(10) {
                at += step
                motion.feed(frame(base.repainted(gif, it + 2), at, ++number))
            }
            val volatile = motion.volatile
            assertNotNull("recording=$recording", volatile)
            assertTrue(volatile!!.contentEquals(gif))
            assertTrue("recording=$recording: the rest of the screen is at rest", motion.still(at + 1))
            // The animation ends, and its samples count again: after a few more screenshots,
            // or, with a recording that sends nothing while nothing changes, after a moment.
            if (recording) {
                assertEquals(at + Motion.ACTIVE_MS + 1, motion.volatileUntil)
                motion.expire(at + Motion.ACTIVE_MS)
                assertNotNull(motion.volatile)
                motion.expire(at + Motion.ACTIVE_MS + 1)
            } else {
                val last = base.repainted(gif, 11)
                repeat(3) {
                    at += step
                    motion.feed(frame(last, at, ++number))
                }
            }
            assertNull(motion.volatile)
            assertEquals(NEVER, motion.volatileUntil)
        }
    }

    @Test
    fun `a recording - what stops animating piece by piece stops being volatile piece by piece`() {
        val motion = Motion(eventDriven = true)
        val base = page(1)
        val left = block(rows = 10..19, cols = 0..4)
        val right = block(rows = 10..19, cols = 5..9)
        val both = BooleanArray(CELLS) { left[it] || right[it] }
        var at = 1_000L
        var number = 0L
        motion.feed(frame(base, at, ++number))
        var cells = base
        repeat(12) {
            at += 100
            cells = cells.repainted(both, it)
            motion.feed(frame(cells, at, ++number))
        }
        assertTrue(motion.volatile!!.contentEquals(both))
        // The left half stops; the right goes on for two more frames.
        repeat(2) {
            at += 100
            cells = cells.repainted(right, it + 12)
            motion.feed(frame(cells, at, ++number))
        }
        assertEquals(at - 200 + Motion.ACTIVE_MS + 1, motion.volatileUntil)
        motion.expire(motion.volatileUntil)
        assertTrue(motion.volatile!!.contentEquals(right))
        assertEquals(at + Motion.ACTIVE_MS + 1, motion.volatileUntil)
        motion.expire(motion.volatileUntil)
        assertNull(motion.volatile)
    }

    @Test
    fun `a whole screen in motion is a scroll, not an animation`() {
        for (recording in listOf(false, true)) {
            val motion = Motion(eventDriven = recording)
            val step = if (recording) 16L else 340L
            var at = 1_000L
            repeat(120) {
                at += step
                motion.feed(frame(page(it), at, it.toLong()))
                assertNull(motion.volatile)
                assertFalse(motion.still(at + 1))
            }
            // It stops: rest is found at the usual time, nothing lingers.
            val rest = page(999)
            motion.feed(frame(rest, at + step, 1_000))
            motion.feed(frame(rest, at + 2 * step, 1_001))
            assertTrue("recording=$recording", motion.still(at + step + Motion.STILL_MS))
        }
    }

    @Test
    fun `our own drawing coming or going is not motion`() {
        val motion = Motion(eventDriven = false)
        val clean = page(1)
        val ours = block(rows = 10..20, cols = 5..30)
        motion.feed(frame(clean, 1_000, 1))
        motion.feed(frame(clean, 1_340, 2))
        assertTrue(motion.still(1_400))
        // Drawn at 1500: the frame before it is older than "sure", the change is ours.
        motion.feed(frame(clean.repainted(ours, 3), 1_680, 3), unsure = ours, sureAt = 1_600)
        assertTrue(motion.still(1_700))
        // The same change with nothing of ours to explain it is motion.
        motion.feed(frame(clean, 2_020, 4), unsure = ours, sureAt = 1_600)
        assertFalse(motion.still(2_100))
    }

    // ---- pacing ----

    @Test
    fun `a burst of requests is allowed, then one every second and a half`() {
        val pacer = Pacer()
        val start = 10_000L
        var now = start
        val sent = ArrayList<Long>()
        // As many as it will let through, for sixteen and a half seconds.
        while (now <= start + 16_500) {
            val wait = pacer.delay(now, attended = true)
            if (wait == 0L) {
                sent += now - start
                pacer.started(now, attended = true)
                now += 1
            } else {
                now += wait
            }
        }
        // The budget of five, and the one that came back while they went out; then the trickle.
        assertEquals(listOf(0L, 300L, 600L, 900L, 1_200L, 1_500L), sent.take(6))
        assertEquals(16, sent.size)
        assertTrue((6 until sent.size).all { sent[it] - sent[it - 1] in 1_499L..1_501L })
        // Left alone, the budget comes back.
        now += 60_000
        repeat(Pacer.BURST) {
            assertEquals(0L, pacer.delay(now, attended = true))
            pacer.started(now, attended = true)
            now += Pacer.MIN_GAP_MS
        }
    }

    @Test
    fun `no two requests closer than the minimum gap`() {
        val pacer = Pacer()
        pacer.started(10_000, attended = true)
        assertEquals(Pacer.MIN_GAP_MS - 100, pacer.delay(10_100, attended = true))
        assertEquals(0L, pacer.delay(10_000 + Pacer.MIN_GAP_MS, attended = true))
    }

    @Test
    fun `requests nobody asked for are spaced further and further apart, until somebody asks`() {
        val pacer = Pacer()
        var now = 10_000L
        val gaps = ArrayList<Long>()
        repeat(8) {
            val wait = pacer.delay(now, attended = false)
            gaps += wait
            now += wait
            pacer.started(now, attended = false)
        }
        assertEquals(listOf(0L, 1_500L, 3_000L, 6_000L, 12_000L, 24_000L, 24_000L, 24_000L), gaps)
        // A request somebody is waiting for goes out at once, and wipes the slate.
        now += Pacer.MIN_GAP_MS
        assertEquals(0L, pacer.delay(now, attended = true))
        pacer.started(now, attended = true)
        now += Pacer.MIN_GAP_MS
        assertEquals(0L, pacer.delay(now, attended = false))
    }

    @Test
    fun `failures space requests out, and the first after a success is the one to report`() {
        val pacer = Pacer()
        pacer.started(10_000, attended = true)
        assertTrue(pacer.failed())
        assertEquals(Pacer.FAILED_BASE_MS, pacer.delay(10_000, attended = true))
        pacer.started(12_000, attended = true)
        assertFalse(pacer.failed())
        assertEquals(2 * Pacer.FAILED_BASE_MS, pacer.delay(12_000, attended = true))
        repeat(10) { pacer.failed() }
        assertEquals(Pacer.FAILED_MAX_MS, pacer.delay(12_000, attended = true))
        pacer.succeeded()
        assertEquals(0L, pacer.delay(13_000, attended = true))
        assertTrue(pacer.failed())
    }

    // ---- memory ----

    @Test
    fun `memory finds a view by its picture and its settings`() {
        val memory = Memory(maxEntries = 4, maxBytes = 1_000)
        val one = memory.put(signature(page(1)), "en", "one", 100)
        memory.put(signature(page(2)), "en", "two", 100)
        assertSame(one, memory.find(signature(page(1)), "en"))
        assertNull(memory.find(signature(page(1)), "ru"))
        assertNull(memory.find(signature(page(3)), "en"))
        // A few samples off is the same view; a dozen is another.
        assertSame(one, memory.find(signature(page(1).repainted(block(0..0, 0..10), 1)), "en"))
        assertNull(memory.find(signature(page(1).repainted(block(0..0, 0..11), 1)), "en"))
        // Unless those are samples that never hold still.
        assertSame(one, memory.find(signature(page(1).repainted(block(0..5, 0..11), 1)), "en", ignore = block(0..5, 0..11)))
        assertEquals(listOf<Any>("two", "one"), memory.payloads("en"))
    }

    @Test
    fun `memory is bounded by count and by bytes, oldest out first`() {
        val byCount = Memory(maxEntries = 2, maxBytes = 1_000_000)
        repeat(3) { byCount.put(signature(page(it)), "en", "p$it", 10) }
        assertEquals(listOf<Any>("p2", "p1"), byCount.payloads("en"))
        val byBytes = Memory(maxEntries = 10, maxBytes = 250)
        repeat(4) { byBytes.put(signature(page(it)), "en", "p$it", 100) }
        assertEquals(listOf<Any>("p3", "p2"), byBytes.payloads("en"))
        assertEquals(200L, byBytes.bytes)
        // One translation is kept however big: it is the one on screen.
        byBytes.put(signature(page(9)), "en", "huge", 5_000)
        assertEquals(listOf<Any>("huge"), byBytes.payloads("en"))
        byBytes.put(signature(page(8)), "en", "small", 10)
        byBytes.trim(1)
        assertEquals(1, byBytes.size)
    }

    @Test
    fun `a new translation of a view replaces the old one`() {
        val memory = Memory(maxEntries = 4, maxBytes = 1_000)
        memory.put(signature(page(1)), "en", "old", 100)
        memory.put(signature(page(1)), "en", "new", 100)
        memory.put(signature(page(1)), "ru", "other", 100)
        assertEquals(listOf<Any>("new"), memory.payloads("en"))
        assertEquals(2, memory.size)
    }

    @Test
    fun `a failed view is retried after three seconds, then after ten, then not at all`() {
        val memory = Memory(maxEntries = 4, maxBytes = 1_000)
        val view = signature(page(1))
        val first = memory.fail(view, "en", now = 1_000)
        assertTrue(first.failed)
        assertNull(first.payload)
        assertEquals(4_000L, first.retryAt)
        assertFalse(first.retryDue(3_999))
        assertTrue(first.retryDue(4_000))
        val second = memory.fail(view, "en", now = 4_000)
        assertEquals(14_000L, second.retryAt)
        val third = memory.fail(view, "en", now = 14_000)
        assertEquals(NEVER, third.retryAt)
        assertFalse(third.retryDue(Long.MAX_VALUE - 1))
        assertEquals(1, memory.size)
        // A success forgets the failures.
        memory.put(view, "en", "done", 10)
        assertEquals(4_000L - 1_000L + 20_000L, memory.fail(view, "en", now = 20_000).retryAt)
    }
}

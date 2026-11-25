package io.github.bropines.lens.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live translator's decisions, played out on a simulated phone (see [Sim]). */
class LiveBrainTest {
    private val paragraph = block(rows = 10..20, cols = 5..30)

    /** Translates the page on screen and waits until the translation is up and settled. */
    private fun Sim.translated(payload: String): Int {
        val ticket = awaitRequest()
        run(600)
        done(ticket, payload, paragraph)
        run(1_200)
        return ticket
    }

    // ---- when to ask ----

    @Test
    fun `screenshots - a page at rest is asked for once, after two frames agree`() {
        val sim = Sim(eventDriven = false)
        val start = sim.now
        val ticket = sim.awaitRequest()
        // One frame says nothing about rest: the second, a third of a second on, does.
        assertTrue("asked ${sim.requestedAt[0] - start} ms in", sim.requestedAt[0] - start in 400..560)
        assertTrue(sim.busy)
        sim.run(600)
        sim.done(ticket, "one", paragraph)
        assertFalse(sim.busy)
        sim.run(10_000)
        assertEquals(listOf<Any>("one"), sim.shown)
        assertEquals(listOf(1), sim.requests)
        assertEquals(0, sim.hides)
    }

    @Test
    fun `screenshots - nothing is asked for while the page scrolls, and no time is lost after it`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        // Two seconds of scrolling: every frame shows another picture.
        repeat(20) {
            sim.page = page(100 + it)
            sim.run(100)
        }
        assertEquals("a request in the middle of a scroll", listOf(1), sim.requests)
        assertEquals(1, sim.hides)
        sim.page = page(2)
        val stopped = sim.now
        sim.awaitRequest()
        // The first frame of the resting page, the second to confirm it, and their way here.
        assertTrue("asked ${sim.now - stopped} ms after the scroll", sim.now - stopped <= 2 * 340 + Sim.SCREENSHOT_MS + 20)
        assertEquals(emptyList<Int>(), sim.cancelled)
    }

    @Test
    fun `a recording - a page is asked for a fifth of a second after it stops, with no frame needed`() {
        val sim = Sim(eventDriven = true)
        val start = sim.now
        val ticket = sim.awaitRequest()
        assertTrue("asked ${sim.requestedAt[0] - start} ms in", sim.requestedAt[0] - start in 200..260)
        sim.run(500)
        sim.done(ticket, "one", paragraph)
        sim.run(10_000)
        assertEquals(listOf(1), sim.requests)
        assertEquals(0, sim.hides)
    }

    @Test
    fun `a recording - scrolling asks for nothing until it stops`() {
        val sim = Sim(eventDriven = true)
        sim.translated("one")
        repeat(100) {
            sim.page = page(100 + it)
            sim.run(16)
        }
        assertEquals(listOf(1), sim.requests)
        sim.page = page(2)
        val stopped = sim.now
        sim.awaitRequest()
        assertTrue("asked ${sim.now - stopped} ms after the scroll", sim.now - stopped <= 260)
    }

    @Test
    fun `a request whose page moved on is dropped, and the next page is not made to wait for it`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            sim.awaitRequest()
            sim.run(300)
            sim.page = page(2)
            val changed = sim.now
            sim.run(600)
            assertEquals(listOf(1), sim.cancelled)
            sim.awaitRequest(after = 1)
            assertTrue("recording=$recording: asked ${sim.now - changed} ms after the change", sim.now - changed <= 900)
            // The answer to the dropped request, should it still come, is nobody's.
            sim.done(1, "stale", paragraph)
            assertEquals(emptyList<Any>(), sim.shown)
        }
    }

    // ---- our own drawing ----

    @Test
    fun `the translation on screen is not taken for a change of the page`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            sim.translated("one")
            sim.run(20_000)
            assertEquals("recording=$recording", 0, sim.hides)
            assertEquals(listOf(1), sim.requests)
        }
    }

    @Test
    fun `a change beside the translation hides it and asks again`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            sim.translated("one")
            sim.page = sim.page.repainted(block(rows = 40..50, cols = 0..39), shade = 1)
            sim.run(500)
            assertEquals("recording=$recording", 1, sim.hides)
            assertNull(sim.drawn)
            sim.awaitRequest(after = 1)
        }
    }

    @Test
    fun `fewer changed samples than make a new view leave the translation alone`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        // A clock ticking over, a "like" turning red: eleven samples.
        sim.page = sim.page.repainted(block(rows = 60..60, cols = 0..10), shade = 2)
        sim.run(3_000)
        assertEquals(0, sim.hides)
        assertEquals(listOf(1), sim.requests)
    }

    // ---- memory ----

    @Test
    fun `a page seen before is shown from memory, at once and without a request`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            val first = sim.page
            sim.translated("one")
            sim.page = page(2)
            sim.translated("two")
            assertEquals(listOf(1, 2), sim.requests)
            sim.page = first
            val back = sim.now
            sim.run(900)
            assertEquals("recording=$recording", listOf<Any>("one", "two", "one"), sim.shown)
            assertEquals(listOf(1, 2), sim.requests)
            assertEquals(listOf(1, 2), sim.snaps)
            val at = sim.log.first { it.endsWith("shown from memory") }.substringBefore(':').toLong()
            // No waiting for "rest": the picture is known. A recording needs a moment without
            // motion; screenshots, the first one taken after our old translation left the screen.
            val limit = if (recording) 140 else 2 * 340 + Sim.SCREENSHOT_MS + 20
            assertTrue("recording=$recording: shown ${at - back} ms after coming back", at - back <= limit)
        }
    }

    @Test
    fun `a page with nothing to translate is remembered as such`() {
        val sim = Sim(eventDriven = false)
        val blank = sim.page
        sim.empty(sim.awaitRequest())
        assertFalse(sim.busy)
        sim.page = page(2)
        sim.translated("two")
        sim.page = blank
        sim.run(5_000)
        assertEquals(listOf(1, 2), sim.requests)
        assertNull(sim.drawn)
    }

    @Test
    fun `other languages mean another translation of the same page`() {
        val sim = Sim(eventDriven = true)
        sim.translated("one")
        sim.brain.settings("ru", sim.now)
        assertNull(sim.drawn)
        sim.awaitRequest(after = 1)
        // And back: the first one is still known.
        sim.brain.settings("en", sim.now)
        sim.run(1_000)
        assertEquals(listOf<Any>("one", "one"), sim.shown)
        assertEquals(listOf(2), sim.cancelled)
    }

    // ---- touch ----

    @Test
    fun `a finger on the screen hides the translation at once, and a tap that changes nothing brings it back`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            sim.translated("one")
            sim.touch()
            assertNull("recording=$recording: hidden the moment the finger lands", sim.drawn)
            assertEquals(1, sim.hides)
            sim.run(200)
            assertNull("not back while the finger may be starting a scroll", sim.drawn)
            sim.run(600)
            assertNotNull("recording=$recording: back, since nothing changed", sim.drawn)
            assertEquals(listOf<Any>("one", "one"), sim.shown)
            assertEquals(listOf(1), sim.requests)
        }
    }

    @Test
    fun `a finger that scrolls leaves the translation hidden until the new page rests`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        sim.touch()
        repeat(12) {
            sim.page = page(200 + it)
            sim.run(100)
        }
        assertEquals(listOf<Any>("one"), sim.shown)
        assertEquals(listOf(1), sim.requests)
        sim.page = page(3)
        sim.awaitRequest(after = 1)
    }

    @Test
    fun `an area keeps its translation when the screen is touched`() {
        val sim = Sim(eventDriven = false, hideOnTouch = false)
        sim.translated("one")
        sim.touch()
        sim.run(1_000)
        assertEquals(0, sim.hides)
        assertNotNull(sim.drawn)
    }

    @Test
    fun `a touch during a request does not start it over`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            val ticket = sim.awaitRequest()
            sim.run(100)
            sim.touch()
            sim.run(900)
            assertEquals("recording=$recording", listOf(1), sim.requests)
            assertEquals(emptyList<Int>(), sim.cancelled)
            sim.done(ticket, "one", paragraph)
            sim.run(600)
            assertNotNull(sim.drawn)
            assertEquals(listOf<Any>("one"), sim.shown)
        }
    }

    @Test
    fun `an answer that comes while a finger hides the page is shown once the finger has done nothing`() {
        val sim = Sim(eventDriven = false)
        val ticket = sim.awaitRequest()
        sim.touch()
        sim.run(40)
        sim.done(ticket, "one", paragraph)
        assertNull(sim.drawn)
        sim.run(900)
        assertNotNull(sim.drawn)
        assertEquals(listOf(1), sim.requests)
    }

    // ---- what never holds still ----

    @Test
    fun `a corner that never stops moving neither blocks the translation nor takes it away`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            val spinner = block(rows = 60..65, cols = 30..35)
            val still = sim.page
            var tick = 0
            fun spin(ms: Long) {
                var left = ms
                while (left > 0) {
                    sim.page = still.repainted(spinner, shade = tick++)
                    sim.run(100)
                    left -= 100
                }
            }
            spin(3_000)
            assertEquals("recording=$recording: asked despite the spinner", 1, sim.requests.size)
            sim.done(sim.requests[0], "one", paragraph)
            spin(8_000)
            assertEquals("recording=$recording", 0, sim.hides)
            assertEquals(listOf(1), sim.requests)
            val volatile = sim.brain.volatile
            assertNotNull(volatile)
            assertTrue(spinner.indices.all { !spinner[it] || volatile!![it] })
            assertEquals(spinner.count { it }, volatile!!.count { it })
        }
    }

    @Test
    fun `a screen that is all motion is not translated`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            repeat(80) {
                sim.page = page(300 + it)
                sim.run(100)
            }
            assertEquals("recording=$recording", emptyList<Int>(), sim.requests)
            assertNull(sim.brain.volatile)
        }
    }

    // ---- failures ----

    @Test
    fun `a failed page is not asked for again at once - twice more by itself, then left alone`() {
        val sim = Sim(eventDriven = true)
        sim.fail(sim.awaitRequest())
        assertTrue(sim.failed)
        assertFalse(sim.busy)
        val failedAt = sim.now
        sim.run(2_800)
        assertEquals(listOf(1), sim.requests)
        sim.awaitRequest(after = 1, limit = 600)
        assertTrue(sim.now - failedAt in 3_000..3_400)
        sim.fail(2)
        sim.run(9_500)
        assertEquals(listOf(1, 2), sim.requests)
        sim.awaitRequest(after = 2, limit = 1_000)
        sim.fail(3)
        sim.run(120_000)
        assertEquals(listOf(1, 2, 3), sim.requests)
        // One message for the lot: the bubble says the rest.
        assertEquals(listOf(true, false, false), sim.failures)
        assertTrue(sim.failed)
    }

    @Test
    fun `after a failure a new page is asked for, and success clears the error`() {
        val sim = Sim(eventDriven = false)
        sim.fail(sim.awaitRequest())
        sim.page = page(2)
        sim.run(1_000)
        assertFalse("the error belongs to the page that is gone", sim.failed)
        // Failures slow the asking down: two seconds after the first.
        val ticket = sim.awaitRequest(after = 1, limit = 3_000)
        assertTrue(sim.requestedAt[1] - sim.requestedAt[0] >= Pacer.FAILED_BASE_MS)
        sim.done(ticket, "two", paragraph)
        assertFalse(sim.failed)
        // And it comes back as a first failure, worth telling again.
        sim.page = page(3)
        sim.fail(sim.awaitRequest(after = 2))
        assertEquals(listOf(true, true), sim.failures)
    }

    // ---- loops ----

    @Test
    fun `a corner that changes by itself is translated less and less often`() {
        val sim = Sim(eventDriven = true)
        sim.translated("one")
        val banner = block(rows = 60..65, cols = 5..30)
        var answered = 1
        // A carousel turning every two seconds for a minute, each slide a new one, and nobody touching the screen.
        repeat(30) { turn ->
            sim.page = sim.page.scrambled(banner, variant = turn)
            repeat(20) {
                sim.run(100)
                if (sim.requests.size > answered) sim.done(sim.requests[answered++], "banner $turn", paragraph)
            }
        }
        val asked = sim.requests.size - 1
        // 0, 1.5, 3, 6, 12, 24 seconds apart, then every 24.
        assertTrue("asked $asked times for 30 turns of the carousel", asked in 5..7)
        // Every turn was taken in, though: the rest of the page had its translation back at once.
        assertTrue("taken ${sim.snaps.size - 1} times", sim.snaps.size - 1 >= 28)
    }

    @Test
    fun `a corner that keeps coming back to the same few pictures is served from memory`() {
        val sim = Sim(eventDriven = true)
        sim.translated("one")
        val banner = block(rows = 60..65, cols = 5..30)
        var answered = 1
        // Three slides going round for a minute.
        repeat(30) { turn ->
            sim.page = sim.page.repainted(banner, shade = turn % 3)
            repeat(20) {
                sim.run(100)
                if (sim.requests.size > answered) sim.done(sim.requests[answered++], "slide ${turn % 3}", paragraph)
            }
        }
        assertEquals("one request a slide", 3, sim.requests.size - 1)
        assertEquals(30, sim.shown.size - 1)
    }

    @Test
    fun `the same corner changing under the user's finger is translated every time`() {
        val sim = Sim(eventDriven = true)
        sim.translated("one")
        val answer = block(rows = 60..65, cols = 5..30)
        var answered = 1
        // A quiz: tap, the question changes; twenty of them.
        repeat(20) { turn ->
            sim.touch()
            sim.run(300)
            sim.page = sim.page.scrambled(answer, variant = turn)
            repeat(20) {
                sim.run(100)
                if (sim.requests.size > answered) sim.done(sim.requests[answered++], "question $turn", paragraph)
            }
        }
        assertEquals(20, sim.requests.size - 1)
    }

    @Test
    fun `whole new pages are translated every time, touched or not`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        var answered = 1
        // Pages turned with a volume key every three seconds: no touch at all.
        repeat(15) { turn ->
            sim.page = page(500 + turn)
            repeat(30) {
                sim.run(100)
                if (sim.requests.size > answered) sim.done(sim.requests[answered++], "page $turn", paragraph)
            }
        }
        assertEquals(15, sim.requests.size - 1)
    }

    // ---- out of the way ----

    @Test
    fun `while the user is busy with the bubble nothing is asked or shown, and afterwards memory serves`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            sim.translated("one")
            sim.brain.block()
            assertNull(sim.drawn)
            assertEquals(0L, sim.brain.cadence(sim.now))
            sim.run(5_000)
            assertEquals(listOf(1), sim.requests)
            sim.brain.unblock(sim.now)
            // A recording sends no frame for a screen that does not change: this must not wait for one.
            sim.run(900)
            assertEquals("recording=$recording", listOf<Any>("one", "one"), sim.shown)
            assertEquals(listOf(1), sim.requests)
        }
    }

    @Test
    fun `blocking drops the request in flight`() {
        val sim = Sim(eventDriven = false)
        sim.awaitRequest()
        sim.brain.block()
        assertEquals(listOf(1), sim.cancelled)
        assertFalse(sim.busy)
        sim.brain.unblock(sim.now)
        sim.awaitRequest(after = 1)
    }

    // ---- previews ----

    @Test
    fun `pieces of an earlier translation go up while the request runs, and the answer replaces them`() {
        for (recording in listOf(false, true)) {
            val sim = Sim(eventDriven = recording)
            val ticket = sim.awaitRequest()
            val kept = block(rows = 30..35, cols = 0..39)
            sim.previewOf[ticket] = kept
            sim.brain.onPreviewReady(ticket, sim.now)
            assertEquals(1, sim.previews)
            sim.run(2_000)
            assertEquals("recording=$recording: our own preview is not a change of the page", 0, sim.hides)
            assertEquals(emptyList<Int>(), sim.cancelled)
            sim.done(ticket, "one", paragraph)
            sim.run(2_000)
            assertEquals(listOf<Any>("one"), sim.shown)
            assertTrue(sim.drawn.contentEquals(paragraph))
            assertEquals(0, sim.hides)
            assertEquals(listOf(1), sim.requests)
        }
    }

    @Test
    fun `a preview for a request that is gone is not drawn`() {
        val sim = Sim(eventDriven = true)
        val ticket = sim.awaitRequest()
        sim.page = page(2)
        sim.run(100)
        sim.previewOf[ticket] = paragraph
        sim.brain.onPreviewReady(ticket, sim.now)
        assertEquals(0, sim.previews)
    }

    // ---- how often to look ----

    @Test
    fun `screenshots are taken less often once the page has been still for a while`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        assertEquals(LiveBrain.FAST_MS, sim.brain.cadence(sim.now))
        // No touch has ever been seen: the slow paces are not trusted.
        sim.run(10_000)
        assertEquals(500L, sim.brain.cadence(sim.now))
        sim.touch()
        assertEquals(LiveBrain.FAST_MS, sim.brain.cadence(sim.now))
        sim.run(5_000)
        assertEquals(600L, sim.brain.cadence(sim.now))
        sim.run(30_000)
        assertEquals(1_000L, sim.brain.cadence(sim.now))
        sim.run(90_000)
        assertEquals(1_500L, sim.brain.cadence(sim.now))
        val shots = sim.shotsAt.size
        sim.run(15_000)
        assertEquals(10, sim.shotsAt.size - shots)
        // And a change is still seen, only later.
        sim.page = page(2)
        sim.awaitRequest(after = 1, limit = 3_000)
    }

    @Test
    fun `after a touch the next screenshot is timed to show what the finger did`() {
        val sim = Sim(eventDriven = false)
        sim.translated("one")
        sim.run(40_000)
        val touched = sim.now
        sim.touch()
        sim.run(1_000)
        val next = sim.shotsAt.first { it > touched }
        assertTrue("taken ${next - touched} ms after the touch", next - touched in LiveBrain.TOUCH_HOLD_MS..LiveBrain.TOUCH_HOLD_MS + 8)
    }
}

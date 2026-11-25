package io.github.bropines.lens.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedWorkTest {
    /** A scope like the scanner's own: not the callers', on the test's clock. */
    private fun TestScope.workScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun workAskedForTwiceRunsOnceAndBothGetIt() = runTest {
        val scope = workScope()
        val shared = SharedWork<String, Int>(scope)
        var runs = 0
        val gate = CompletableDeferred<Int>()
        val first = async { shared.run("page") { runs++; gate.await() } }
        val second = async { shared.run("page") { runs++; gate.await() } }
        runCurrent()
        assertEquals(1, runs)
        assertEquals(1, shared.size())

        gate.complete(7)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        runCurrent()
        assertEquals(0, shared.size())
        scope.cancel()
    }

    @Test
    fun aCallerGivingUpLeavesTheOtherItsResult() = runTest {
        val scope = workScope()
        val shared = SharedWork<String, Int>(scope)
        var stopped = false
        val gate = CompletableDeferred<Int>()
        val work: suspend () -> Int = {
            try {
                gate.await()
            } catch (e: CancellationException) {
                stopped = true
                throw e
            }
        }
        val leaving = async { shared.run("page", work) }
        val staying = async { shared.run("page", work) }
        runCurrent()

        leaving.cancel()
        runCurrent()
        assertFalse(stopped, "the work must go on for the caller still waiting")

        gate.complete(3)
        assertEquals(3, staying.await())
        scope.cancel()
    }

    @Test
    fun workNobodyWaitsForIsStoppedAndStartsAfreshNextTime() = runTest {
        val scope = workScope()
        val shared = SharedWork<String, Int>(scope)
        var stopped = 0
        var runs = 0
        val never = CompletableDeferred<Int>()
        val only = async {
            shared.run("page") {
                runs++
                try {
                    never.await()
                } catch (e: CancellationException) {
                    stopped++
                    throw e
                }
            }
        }
        runCurrent()
        only.cancel()
        runCurrent()
        assertEquals(1, stopped)
        assertEquals(0, shared.size())

        // The stopped work left nothing behind that a later caller could get stuck on.
        assertEquals(5, shared.run("page") { runs++; 5 })
        assertEquals(2, runs)
        scope.cancel()
    }

    @Test
    fun aFailureReachesEveryoneWaitingAndIsNotRemembered() = runTest {
        val scope = workScope()
        val shared = SharedWork<String, Int>(scope)
        val gate = CompletableDeferred<Int>()
        val first = async { runCatching { shared.run("page") { gate.await() } } }
        val second = async { runCatching { shared.run("page") { gate.await() } } }
        runCurrent()

        gate.completeExceptionally(IllegalStateException("no network"))
        assertTrue(first.await().exceptionOrNull() is IllegalStateException)
        assertTrue(second.await().exceptionOrNull() is IllegalStateException)
        runCurrent()
        assertEquals(0, shared.size())

        assertEquals(1, shared.run("page") { 1 })
        assertFailsWith<IllegalArgumentException> { shared.run("page") { throw IllegalArgumentException("again") } }
        scope.cancel()
    }

    @Test
    fun differentKeysRunSideBySide() = runTest {
        val scope = workScope()
        val shared = SharedWork<String, String>(scope)
        val one = CompletableDeferred<String>()
        val two = CompletableDeferred<String>()
        val a = async { shared.run("1") { one.await() } }
        val b = async { shared.run("2") { two.await() } }
        runCurrent()
        assertEquals(2, shared.size())
        two.complete("second")
        assertEquals("second", b.await())
        one.complete("first")
        assertEquals("first", a.await())
        scope.cancel()
    }
}

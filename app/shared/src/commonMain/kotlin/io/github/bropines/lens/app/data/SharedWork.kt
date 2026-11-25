package io.github.bropines.lens.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs each keyed piece of work once, however many callers ask for it at the
 * same time.
 *
 * The work runs in [scope], not in a caller's: a caller that gives up (its
 * screen closed, its page swiped past) leaves the others their result, and
 * the work is stopped only once nobody waits for it any more. A failure
 * reaches everyone who was waiting; the next call starts afresh.
 */
internal class SharedWork<K : Any, V>(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val running = HashMap<K, Entry<V>>()

    private class Entry<V> {
        lateinit var work: Deferred<V>
        var waiters = 0
    }

    /** How many pieces of work are under way; for tests. */
    internal suspend fun size(): Int = lock.withLock { running.size }

    suspend fun run(key: K, work: suspend () -> V): V {
        val entry = lock.withLock {
            val existing = running[key]
            (existing ?: Entry<V>().also { fresh ->
                running[key] = fresh
                fresh.work = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        work()
                    } finally {
                        withContext(NonCancellable) { lock.withLock { if (running[key] === fresh) running.remove(key) } }
                    }
                }
            }).also { it.waiters++ }
        }
        try {
            return entry.work.await()
        } finally {
            // Also when this caller was cancelled: the bookkeeping must not be skipped.
            withContext(NonCancellable) {
                lock.withLock {
                    entry.waiters--
                    if (entry.waiters == 0 && !entry.work.isCompleted) {
                        if (running[key] === entry) running.remove(key)
                        entry.work.cancel()
                    }
                }
            }
        }
    }
}

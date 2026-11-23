package io.github.bropines.lens.app.data

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import io.github.bropines.lens.app.data.history.HistoryRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.protocol.LensClient
import io.github.bropines.lens.protocol.ScanOptions
import io.github.bropines.lens.protocol.ScanResult
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scans images through Lens, remembering results so paging back and forth
 * never repeats a request, and joining concurrent requests for the same image.
 */
@SingleIn(AppScope::class)
@Inject
class ScanRepository(private val lens: LensClient, private val history: HistoryRepository) {
    private val log = Logger.withTag("ScanRepository")
    private val lock = Mutex()
    /** Insertion-ordered; a hit is moved to the end, so the first key is the least recently used. */
    private val cache = LinkedHashMap<String, ScanResult>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ScanResult>>()

    suspend fun scan(item: GalleryItem, settings: AppSettings): ScanResult {
        val key = cacheKey(item, settings)
        val (deferred, owner) = lock.withLock {
            cache.remove(key)?.let { hit ->
                cache[key] = hit
                return hit
            }
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<ScanResult>().also { inFlight[key] = it } to true
        }
        if (!owner) return deferred.await()
        try {
            val bytes = item.file.readBytes()
            val result = lens.scan(bytes, settings.toScanOptions())
            put(key, result)
            deferred.complete(result)
            // History is a convenience: a failure to save it must not fail the scan.
            runCatching { history.record(item, bytes, settings, result) }.onFailure { log.w(it) { "history not saved" } }
            return result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    /** Seeds the cache with a result already known, e.g. from history, so opening it costs no request. */
    suspend fun prime(item: GalleryItem, settings: AppSettings, result: ScanResult) = put(cacheKey(item, settings), result)

    private suspend fun put(key: String, result: ScanResult) = lock.withLock {
        cache[key] = result
        if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
    }

    private fun cacheKey(item: GalleryItem, s: AppSettings) =
        "${item.key}|${s.targetLanguage}|${s.sourceLanguage}|${s.ocrLanguage}"

    private companion object {
        const val CACHE_SIZE = 200
    }
}

fun AppSettings.toScanOptions() = ScanOptions(
    ocrLanguage = ocrLanguage,
    targetLanguage = targetLanguage,
    sourceLanguage = sourceLanguage,
)

package io.github.bropines.lens.app.data

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import io.github.bropines.lens.app.data.history.HistoryRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.ondevice.OnDeviceScanner
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.protocol.DetectedObject
import io.github.bropines.lens.protocol.LensException
import io.github.bropines.lens.protocol.PreparedImage
import io.github.bropines.lens.protocol.LensClient
import io.github.bropines.lens.protocol.LensSearchSession
import io.github.bropines.lens.protocol.SearchPageOptions
import io.github.bropines.lens.protocol.ScanOptions
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.app.platform.quarterTurns
import io.github.bropines.lens.app.platform.rotateEncodedImage
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A scan and where it was made: by Lens, or [offline] on the device, which reads and translates less well. */
data class Scanned(val result: ScanResult, val offline: Boolean = false)

/**
 * Scans images through Lens, remembering results so paging back and forth
 * never repeats a request, and joining concurrent requests for the same image.
 *
 * Where the build can scan on the device (see [OnDeviceScanner]) that is the
 * fallback when Lens cannot be reached or refuses, and the only scanner when
 * the user chose never to send images out.
 */
@SingleIn(AppScope::class)
@Inject
class ScanRepository(private val lens: LensClient, private val history: HistoryRepository, services: PlatformServices) {
    private val log = Logger.withTag("ScanRepository")
    private val onDevice: OnDeviceScanner? = services.onDevice?.let(::OnDeviceScanner)
    private val lock = Mutex()
    /** Insertion-ordered; a hit is moved to the end, so the first key is the least recently used. */
    private val cache = LinkedHashMap<String, Scanned>()
    private var cachedBytes = 0L
    private val inFlight = mutableMapOf<String, CompletableDeferred<Scanned>>()
    /** The last few pages uploaded for search; each session's upload stays searchable for ~30 minutes. */
    private val searchTargets = LinkedHashMap<String, SearchTarget>()

    /** Clockwise quarter turns per item key, set in the viewer; kept for the app's run. */
    private val rotations = mutableMapOf<String, Int>()

    fun rotation(item: GalleryItem): Int = rotations[item.key] ?: 0

    fun setRotation(item: GalleryItem, turns: Int) {
        val q = quarterTurns(turns)
        if (q == 0) rotations.remove(item.key) else rotations[item.key] = q
    }

    /** [item]'s bytes as uploaded: turned by [rotation] quarter turns clockwise. */
    private suspend fun uploadBytes(item: GalleryItem, rotation: Int): ByteArray {
        val bytes = item.file.readBytes()
        return if (quarterTurns(rotation) == 0) bytes else withContext(Dispatchers.Default) { rotateEncodedImage(bytes, rotation) }
    }

    /** Scans [item] turned [rotation] quarter turns clockwise, so Lens sees the page as the viewer shows it. */
    suspend fun scan(item: GalleryItem, settings: AppSettings, rotation: Int = 0): Scanned {
        val local = onDevice?.takeIf { settings.onDevice.always }
        // The two scanners give different answers for one page, so each has its own cache entry.
        val key = cacheKey(item, settings, rotation) + if (local != null) "|device" else ""
        val (deferred, owner) = lock.withLock {
            cache.remove(key)?.let { hit ->
                cache[key] = hit
                return hit
            }
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<Scanned>().also { inFlight[key] = it } to true
        }
        if (!owner) return deferred.await()
        try {
            val bytes = uploadBytes(item, rotation)
            // Tall pages (screenshots, webtoon strips) go up in full-resolution bands. Search
            // makes its own untranslated upload (searchSession), so this needs no session.
            val scanned = when {
                local != null -> Scanned(local.scan(bytes, settings, download = true), offline = true)
                else -> try {
                    Scanned(lens.scanTiled(bytes, settings.toScanOptions()))
                } catch (e: Exception) {
                    fallback(bytes, settings, e)
                }
            }
            // A fallback is not remembered: the next look at the page should try Lens again.
            val chosen = !scanned.offline || local != null
            if (chosen) put(key, scanned)
            deferred.complete(scanned)
            // History is a convenience: a failure to save it must not fail the scan.
            if (chosen) {
                runCatching { history.record(item, bytes, settings, scanned.result, quarterTurns(rotation)) }
                    .onFailure { log.w(it) { "history not saved" } }
            }
            return scanned
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    /**
     * The on-device scan that stands in for a Lens request that failed with
     * [error], or [error] again when there is no stand-in: the build has none,
     * the user turned it off, the image itself is unreadable, or it failed too
     * (its error then rides along as suppressed, for the message).
     */
    private suspend fun fallback(image: ByteArray, settings: AppSettings, error: Exception): Scanned {
        val local = onDevice
        if (local == null || !settings.onDevice.fallback || error is CancellationException || error is LensException.UnreadableImage) throw error
        log.w(error) { "Lens failed; scanning on the device" }
        return try {
            // No downloads: the usual reason to be here is that there is no network.
            Scanned(local.scan(image, settings, download = false), offline = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.addSuppressed(e)
            throw error
        }
    }

    /**
     * Scans one prepared frame for the screen translator: Lens, with the same
     * on-device stand-in as [scan], and nothing remembered.
     */
    suspend fun scanFrame(frame: PreparedImage, settings: AppSettings): ScanResult {
        onDevice?.takeIf { settings.onDevice.always }?.let { return it.scan(frame.jpeg, settings, download = true) }
        return try {
            lens.scan(frame, settings.toScanOptions())
        } catch (e: Exception) {
            fallback(frame.jpeg, settings, e).result
        }
    }

    /**
     * The search session holding [item]'s upload, for visual search. A page
     * scanned earlier in this run already has one; a page from history or an
     * expired one is uploaded again first.
     */
    suspend fun searchSession(item: GalleryItem, settings: AppSettings, rotation: Int = 0): SearchTarget {
        // Lens lists the objects in a picture only for a plain request: with the
        // TRANSLATE filter overlay_objects comes back empty. Search therefore has
        // its own session and upload, without translation.
        val key = cacheKey(item, settings, rotation) + "|search"
        lock.withLock { searchTargets[key] }?.let { return it }
        val session = lens.searchSession(PAGE)
        val options = settings.toScanOptions().copy(targetLanguage = null, sourceLanguage = null)
        // Turned like the shown page, so regions drawn on it and object boxes line up.
        val target = SearchTarget(session, session.scan(uploadBytes(item, rotation), options).objects)
        lock.withLock {
            searchTargets[key] = target
            if (searchTargets.size > SESSIONS) searchTargets.remove(searchTargets.keys.first())
        }
        return target
    }

    /** A text-only Google search: no upload. */
    fun searchTextUrl(text: String): String = lens.searchSession(PAGE).searchTextUrl(text)

    /** Seeds the cache with a result already known, e.g. from history, so opening it costs no request. */
    suspend fun prime(item: GalleryItem, settings: AppSettings, result: ScanResult) = put(cacheKey(item, settings), Scanned(result))

    private suspend fun put(key: String, scanned: Scanned) = lock.withLock {
        cache.remove(key)?.let { cachedBytes -= it.result.weight() }
        cache[key] = scanned
        cachedBytes += scanned.result.weight()
        // A page's erase patches can be raw RGBA, megabytes each: bound the bytes, not just the count.
        while (cache.size > 1 && (cache.size > CACHE_SIZE || cachedBytes > CACHE_BYTES)) {
            val oldest = cache.keys.first()
            cache.remove(oldest)?.let { cachedBytes -= it.result.weight() }
        }
    }

    /** Drops the in-memory results (not the ones being fetched): the app is out of sight. */
    fun trim() {
        // Called from the main thread by the system; never wait on a scan for it.
        if (!lock.tryLock()) return
        try {
            cache.clear()
            cachedBytes = 0
            searchTargets.clear()
        } finally {
            lock.unlock()
        }
    }

    /** Roughly what [this] holds in memory: its patches, plus a little for text and boxes. */
    private fun ScanResult.weight(): Long {
        var bytes = 4_096L
        for (p in paragraphs) for (line in p.lines) {
            val patch = line.translationStyle?.background ?: continue
            bytes += patch.image.size + (patch.textMask?.size ?: 0)
        }
        return bytes
    }

    /** The rotation is part of the key: the 0° and 90° scans of a page have different boxes. */
    private fun cacheKey(item: GalleryItem, s: AppSettings, rotation: Int = 0) =
        "${item.key}|${s.targetLanguage}|${s.sourceLanguage}|${s.ocrLanguage}|r${quarterTurns(rotation)}"

    private companion object {
        const val CACHE_SIZE = 200
        /** History keeps every result on disk; this is only the quick path for paging back. */
        const val CACHE_BYTES = 32L * 1024 * 1024
        const val SESSIONS = 12
        /** The results page in the app's language. */
        val PAGE = SearchPageOptions(uiLanguage = "ru")
    }
}

/** A page uploaded for search: the session to search through and the objects Lens found on it. */
class SearchTarget(val session: LensSearchSession, val objects: List<DetectedObject>)

fun AppSettings.toScanOptions() = ScanOptions(
    ocrLanguage = ocrLanguage,
    targetLanguage = targetLanguage,
    sourceLanguage = sourceLanguage,
)

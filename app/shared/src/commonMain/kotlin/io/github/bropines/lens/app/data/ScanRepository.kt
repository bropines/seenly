package io.github.bropines.lens.app.data

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import io.github.bropines.lens.app.data.history.HistoryRepository
import io.github.bropines.lens.app.data.history.PagePlace
import io.github.bropines.lens.app.data.history.ScanStamp
import io.github.bropines.lens.app.data.history.contentHash
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A scan and where it was made: by Lens, or [offline] on the device, which reads and translates less well. */
data class Scanned(val result: ScanResult, val offline: Boolean = false)

/** What the viewer asks of the scanner: [ScanRepository] in the app, a stand-in in tests. */
interface PageScanner {
    fun rotation(item: GalleryItem): Int

    fun setRotation(item: GalleryItem, turns: Int)

    /**
     * Scans [item] turned [rotation] quarter turns clockwise. [fresh] asks the
     * scanner again even when a result is remembered. [place] is where the page
     * sits in its folder, for history.
     */
    suspend fun scan(
        item: GalleryItem,
        settings: AppSettings,
        rotation: Int = 0,
        fresh: Boolean = false,
        place: PagePlace? = null,
    ): Scanned

    suspend fun searchSession(item: GalleryItem, settings: AppSettings, rotation: Int = 0): SearchTarget

    fun searchTextUrl(text: String): String
}

/**
 * Scans images through Lens, remembering results so paging back and forth
 * never repeats a request, and joining concurrent requests for the same image.
 * A page read in an earlier session comes back from history's files, as long
 * as the file and what is asked of it are the same.
 *
 * Where the build can scan on the device (see [OnDeviceScanner]) that is the
 * fallback when Lens cannot be reached or refuses, and the only scanner when
 * the user chose never to send images out.
 */
@SingleIn(AppScope::class)
@Inject
class ScanRepository(private val lens: LensClient, private val history: HistoryRepository, services: PlatformServices) : PageScanner {
    private val log = Logger.withTag("ScanRepository")
    private val onDevice: OnDeviceScanner? = services.onDevice?.let(::OnDeviceScanner)
    private val lock = Mutex()
    /** Insertion-ordered; a hit is moved to the end, so the first key is the least recently used. */
    private val cache = LinkedHashMap<String, Scanned>()
    private var cachedBytes = 0L

    /** Scans run here, not in the viewer's scope: one caller leaving must not fail another's page. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flights = SharedWork<String, Scanned>(scope)
    /** The last few pages uploaded for search; each session's upload stays searchable for ~30 minutes. */
    private val searchTargets = LinkedHashMap<String, SearchTarget>()

    /** Clockwise quarter turns per item key, set in the viewer; kept for the app's run. */
    private val rotations = mutableMapOf<String, Int>()

    override fun rotation(item: GalleryItem): Int = rotations[item.key] ?: 0

    override fun setRotation(item: GalleryItem, turns: Int) {
        val q = quarterTurns(turns)
        if (q == 0) rotations.remove(item.key) else rotations[item.key] = q
    }

    /** [item]'s bytes as uploaded: turned by [rotation] quarter turns clockwise. */
    private suspend fun uploadBytes(item: GalleryItem, rotation: Int): ByteArray = turned(item.file.readBytes(), rotation)

    private suspend fun turned(bytes: ByteArray, rotation: Int): ByteArray =
        if (quarterTurns(rotation) == 0) bytes else withContext(Dispatchers.Default) { rotateEncodedImage(bytes, rotation) }

    /** Scans [item] turned [rotation] quarter turns clockwise, so Lens sees the page as the viewer shows it. */
    override suspend fun scan(item: GalleryItem, settings: AppSettings, rotation: Int, fresh: Boolean, place: PagePlace?): Scanned {
        val local = onDevice?.takeIf { settings.onDevice.always }
        // The two scanners give different answers for one page, so each has its own cache entry.
        val key = cacheKey(item, settings, rotation) + if (local != null) "|device" else ""
        if (!fresh) remembered(key)?.let { return it }
        return flights.run(if (fresh) "$key|fresh" else key) { load(key, item, settings, rotation, local, fresh, place) }
    }

    private suspend fun remembered(key: String): Scanned? = lock.withLock {
        cache.remove(key)?.also { cache[key] = it }
    }

    /** The scan itself: from history's files when this very request was answered before, else from the scanner. */
    private suspend fun load(
        key: String,
        item: GalleryItem,
        settings: AppSettings,
        rotation: Int,
        local: OnDeviceScanner?,
        fresh: Boolean,
        place: PagePlace?,
    ): Scanned {
        if (!fresh) remembered(key)?.let { return it }
        val original = item.file.readBytes()
        val turns = quarterTurns(rotation)
        val stamp = ScanStamp(scanSpec(settings, device = local != null), turns, original.size.toLong(), contentHash(original))
        if (!fresh) {
            history.reuse(item, settings.targetLanguage, stamp)?.let { stored ->
                return Scanned(stored, offline = local != null).also { put(key, it) }
            }
        }
        val bytes = turned(original, turns)
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
        if (chosen) {
            put(key, scanned)
            // History is a convenience: saving it must neither hold the page up nor fail the scan.
            scope.launch {
                runCatching { history.record(item, bytes, settings, scanned.result, turns, stamp, place) }
                    .onFailure { log.w(it) { "history not saved" } }
            }
        }
        return scanned
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
    override suspend fun searchSession(item: GalleryItem, settings: AppSettings, rotation: Int): SearchTarget {
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
    override fun searchTextUrl(text: String): String = lens.searchSession(PAGE).searchTextUrl(text)

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

/** How a scan was asked for, as history stores it: the source language, the recognition hint and the scanner. */
internal fun scanSpec(settings: AppSettings, device: Boolean): String =
    "${settings.sourceLanguage ?: "auto"}|${settings.ocrLanguage}|${if (device) "device" else "lens"}"

/** A page uploaded for search: the session to search through and the objects Lens found on it. */
class SearchTarget(val session: LensSearchSession, val objects: List<DetectedObject>)

fun AppSettings.toScanOptions() = ScanOptions(
    ocrLanguage = ocrLanguage,
    targetLanguage = targetLanguage,
    sourceLanguage = sourceLanguage,
)

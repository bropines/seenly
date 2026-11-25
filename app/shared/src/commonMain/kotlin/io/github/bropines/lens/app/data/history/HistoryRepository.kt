package io.github.bropines.lens.app.data.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.ui.viewer.readableText
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where in a folder a page sits, so history can show a chapter as one stack, in order. */
data class PagePlace(val folderKey: String, val folderTitle: String, val pageIndex: Int)

/**
 * What identifies one scan of one file besides its name and target language:
 * how it was asked for and what the file held. A stored result is taken for a
 * new request only when all of it matches.
 */
data class ScanStamp(val spec: String, val rotation: Int, val contentSize: Long, val contentHash: Long)

/**
 * Every scan, kept locally: the picture copied into app storage, the Lens
 * result as JSON, and a searchable row. Reopening an entry needs no network,
 * and a page read before is not asked of Lens again. Nothing leaves the device.
 */
@SingleIn(AppScope::class)
@Inject
class HistoryRepository(private val services: PlatformServices) {
    /** A stand-in for the database, set by the constructor tests and previews use. */
    private var givenDao: HistoryDao? = null

    /** Over [dao] instead of the database in [services]: for tests and previews, which cannot open one. */
    constructor(services: PlatformServices, dao: HistoryDao) : this(services) {
        givenDao = dao
    }

    private val dao: HistoryDao by lazy {
        givenDao ?: services.databaseBuilder()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .history()
    }
    private val dir = Path(services.dataDir, "history")
    private val json = Json { ignoreUnknownKeys = true }

    fun entries(query: String): Flow<List<HistoryEntry>> =
        if (query.isBlank()) dao.all() else dao.search(query.trim())

    /** The newest [limit] entries, for the home screen. */
    fun recent(limit: Int): Flow<List<HistoryEntry>> = dao.recent(limit)

    /** Which pictures (by [GalleryItem.key]) already have a translation into [target]. */
    fun translatedKeys(target: String): Flow<Set<String>> = dao.keys(target).map { it.toSet() }.distinctUntilChanged()

    /** Whether [item] is one of our own copies, i.e. was opened from history. */
    fun isOwnCopy(item: GalleryItem): Boolean = item.historyId != null || item.file.toString().contains(dir.toString())

    /**
     * The result stored for [item] in [target], when it is the scan [stamp]
     * describes: the same file, asked for the same way. An item opened from
     * history is its own stored result, however it was asked for back then.
     */
    suspend fun reuse(item: GalleryItem, target: String, stamp: ScanStamp): ScanResult? = withContext(Dispatchers.IO) {
        val entry = runCatching {
            val own = item.historyId?.let { dao.byId(it) }
            if (own != null) {
                own.takeIf { it.targetLanguage == target && stamp.rotation == 0 }
            } else {
                dao.find(item.key, target)?.takeIf {
                    it.scanSpec == stamp.spec && it.rotation == stamp.rotation &&
                        it.contentSize == stamp.contentSize && it.contentHash == stamp.contentHash
                }
            }
        }.getOrNull() ?: return@withContext null
        load(entry)
    }

    @OptIn(ExperimentalTime::class)
    /** [imageBytes] are what was scanned: the picture turned [rotation] quarter turns clockwise. */
    suspend fun record(
        item: GalleryItem,
        imageBytes: ByteArray,
        settings: AppSettings,
        result: ScanResult,
        rotation: Int = 0,
        stamp: ScanStamp? = null,
        place: PagePlace? = null,
    ) = withContext(Dispatchers.IO) {
        if (isOwnCopy(item) || result.paragraphs.isEmpty()) return@withContext
        SystemFileSystem.createDirectories(dir)
        val stem = stableName(item.key)
        val existing = dao.find(item.key, settings.targetLanguage)
        // The same name holding other pixels (a reused file name): the old copy is not this picture.
        val replaced = stamp != null && existing != null && existing.contentSize != 0L &&
            (existing.contentSize != stamp.contentSize || existing.contentHash != stamp.contentHash)
        // Each turn has its own copy: the result's boxes fit only the picture they were read from.
        val image = Path(dir, if (rotation == 0) "$stem.img" else "$stem-r$rotation.img")
        if (replaced || !SystemFileSystem.exists(image)) SystemFileSystem.sink(image).buffered().use { it.write(imageBytes) }
        val resultFile = Path(dir, "$stem-${settings.targetLanguage}.json")
        SystemFileSystem.sink(resultFile).buffered().use { it.writeString(json.encodeToString(ScanResult.serializer(), result)) }

        dao.insert(
            HistoryEntry(
                id = existing?.id ?: 0,
                imageKey = item.key,
                title = item.name,
                imagePath = image.toString(),
                resultPath = resultFile.toString(),
                sourceLanguage = result.paragraphs.firstNotNullOfOrNull { it.translation?.sourceLanguage } ?: result.language,
                targetLanguage = settings.targetLanguage,
                originalText = result.readableText().take(TEXT_LIMIT),
                translatedText = result.translation.orEmpty().take(TEXT_LIMIT),
                createdAt = Clock.System.now().toEpochMilliseconds(),
                scanSpec = stamp?.spec.orEmpty(),
                rotation = rotation,
                contentSize = stamp?.contentSize ?: 0,
                contentHash = stamp?.contentHash ?: 0,
                folderKey = place?.folderKey,
                folderTitle = place?.folderTitle,
                pageIndex = place?.pageIndex ?: 0,
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
            ),
        )
        if (replaced) {
            // The other languages' entries for this name describe the picture that was there before.
            dao.byKey(item.key).filter { it.targetLanguage != settings.targetLanguage }.forEach { stale ->
                dao.delete(stale.id)
                runCatching { SystemFileSystem.delete(Path(stale.resultPath), mustExist = false) }
            }
        }
        // Re-read at another turn: the old copy goes once no other language's entry shows it.
        val previous = existing?.imagePath
        if (previous != null && previous != image.toString() && dao.everything().none { it.imagePath == previous }) {
            runCatching { SystemFileSystem.delete(Path(previous), mustExist = false) }
        }
    }

    suspend fun load(entry: HistoryEntry): ScanResult? = withContext(Dispatchers.IO) {
        runCatching {
            SystemFileSystem.source(Path(entry.resultPath)).buffered().use { json.decodeFromString(ScanResult.serializer(), it.readString()) }
        }.getOrNull()
    }

    suspend fun delete(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        dao.delete(entry.id)
        runCatching { SystemFileSystem.delete(Path(entry.resultPath), mustExist = false) }
        // The picture may be shared by entries in other languages.
        if (dao.everything().none { it.imagePath == entry.imagePath }) {
            runCatching { SystemFileSystem.delete(Path(entry.imagePath), mustExist = false) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clear()
        if (SystemFileSystem.exists(dir)) SystemFileSystem.list(dir).forEach { runCatching { SystemFileSystem.delete(it) } }
    }

    suspend fun readImage(entry: HistoryEntry): ByteArray = withContext(Dispatchers.IO) {
        SystemFileSystem.source(Path(entry.imagePath)).buffered().use { it.readByteArray() }
    }

    /** A filesystem-safe, stable name for a source key. */
    private fun stableName(key: String): String {
        var h = 1125899906842597L
        for (c in key) h = 31 * h + c.code
        return h.toULong().toString(16)
    }

    private companion object {
        const val TEXT_LIMIT = 4000
    }
}

/**
 * A quick fingerprint of a file's bytes (FNV-1a): all of a small file, the
 * first and last quarter megabyte of a large one. Together with the length it
 * tells a re-used file name from the same picture; it is not a checksum.
 */
fun contentHash(bytes: ByteArray): Long {
    var h = -0x340d631b7bdddcdbL
    fun feed(from: Int, until: Int) {
        for (i in from until until) {
            h = h xor (bytes[i].toLong() and 0xff)
            h *= 0x100000001b3L
        }
    }
    if (bytes.size <= 2 * HASH_SPAN) {
        feed(0, bytes.size)
    } else {
        feed(0, HASH_SPAN)
        feed(bytes.size - HASH_SPAN, bytes.size)
    }
    return h
}

private const val HASH_SPAN = 256 * 1024

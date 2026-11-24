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

/**
 * Every scan, kept locally: the picture copied into app storage, the Lens
 * result as JSON, and a searchable row. Reopening an entry needs no network.
 * Nothing leaves the device.
 */
@SingleIn(AppScope::class)
@Inject
class HistoryRepository(private val services: PlatformServices) {
    private val db: LensDatabase = services.databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val dao = db.history()
    private val dir = Path(services.dataDir, "history")
    private val json = Json { ignoreUnknownKeys = true }

    fun entries(query: String): Flow<List<HistoryEntry>> =
        if (query.isBlank()) dao.all() else dao.search(query.trim())

    /** Whether [item] is one of our own copies, i.e. was opened from history. */
    fun isOwnCopy(item: GalleryItem): Boolean = item.key.contains(dir.toString())

    @OptIn(ExperimentalTime::class)
    /** [imageBytes] are what was scanned: the picture turned [rotation] quarter turns clockwise. */
    suspend fun record(item: GalleryItem, imageBytes: ByteArray, settings: AppSettings, result: ScanResult, rotation: Int = 0) = withContext(Dispatchers.IO) {
        if (isOwnCopy(item) || result.paragraphs.isEmpty()) return@withContext
        SystemFileSystem.createDirectories(dir)
        val stem = stableName(item.key)
        // Each turn has its own copy: the result's boxes fit only the picture they were read from.
        val image = Path(dir, if (rotation == 0) "$stem.img" else "$stem-r$rotation.img")
        if (!SystemFileSystem.exists(image)) SystemFileSystem.sink(image).buffered().use { it.write(imageBytes) }
        val resultFile = Path(dir, "$stem-${settings.targetLanguage}.json")
        SystemFileSystem.sink(resultFile).buffered().use { it.writeString(json.encodeToString(ScanResult.serializer(), result)) }

        val existing = dao.find(item.key, settings.targetLanguage)
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
            ),
        )
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

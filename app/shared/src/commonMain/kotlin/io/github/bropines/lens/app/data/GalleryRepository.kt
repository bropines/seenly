package io.github.bropines.lens.app.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.data.history.HistoryEntry
import io.github.bropines.lens.app.data.history.HistoryRepository
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.PlatformFileSerializer
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One image the viewer can page to. [key] identifies it in caches across
 * sessions. [historyId] is set for a picture opened from history: it is the
 * stored copy of that entry, and its result is already on disk.
 */
class GalleryItem(val file: PlatformFile, private val displayName: String? = null, val historyId: Long? = null) {
    // Two history entries can share one stored picture (the same image in two languages); each is a page of its own.
    val key: String = if (historyId == null) file.toString() else "$file#$historyId"
    val name: String get() = displayName ?: file.name
}

/** A history entry as a page: its stored copy, under the name it was scanned with. */
fun historyItem(entry: HistoryEntry): GalleryItem = GalleryItem(PlatformFile(entry.imagePath), entry.title, historyId = entry.id)

/** Where a gallery came from: what closing it goes back to, and whether it has a grid of its own. */
enum class GalleryOrigin {
    Files,
    Folder,
    History,

    /** A shot, or pictures picked from the camera screen or the camera button: they open in the mode last picked for one. */
    Camera,
}

/** What the viewer pages through: some shared or picked files, a whole folder, or history. */
data class Gallery(
    val title: String,
    val items: List<GalleryItem>,
    val startIndex: Int = 0,
    val folder: PlatformFile? = null,
    val origin: GalleryOrigin = if (folder != null) GalleryOrigin.Folder else GalleryOrigin.Files,
    /** What the viewer shows first; null is the translation. */
    val startMode: ViewMode? = null,
    /** Handed over by another app (share, "open with", the assistant): closing it goes back to that app. */
    val fromOutside: Boolean = false,
    /** Tells two openings of the same pictures apart. */
    val id: Long = nextGalleryId(),
) {
    /** The folder's identity in history and in the reading positions. */
    val folderKey: String? get() = folder?.toString()
}

@OptIn(ExperimentalAtomicApi::class)
private val galleryIds = AtomicLong(0)

@OptIn(ExperimentalAtomicApi::class)
private fun nextGalleryId(): Long = galleryIds.incrementAndFetch()

/** What the viewer keeps between sessions: which pictures are translated already, and where reading stopped. */
interface ViewerLibrary {
    /** Keys of the pictures that have a stored translation into [target]. */
    fun translatedKeys(target: String): Flow<Set<String>>

    /** Remembers that [gallery] was left at page [index]. */
    suspend fun savePosition(gallery: Gallery, index: Int)
}

@SingleIn(AppScope::class)
@Inject
class GalleryRepository(services: PlatformServices, private val history: HistoryRepository) : ViewerLibrary {
    /** The folders read lately and where each was left. */
    val progress = ReadingProgress(services.dataDir)

    private val _current = MutableStateFlow<Gallery?>(null)

    /** The gallery the viewer shows; null while the viewer is closed. */
    val current: StateFlow<Gallery?> = _current.asStateFlow()

    fun openFiles(
        files: List<PlatformFile>,
        title: String? = null,
        mode: ViewMode? = null,
        origin: GalleryOrigin = GalleryOrigin.Files,
        fromOutside: Boolean = false,
    ) {
        if (files.isEmpty()) return
        openItems(files.map { GalleryItem(it) }, title ?: files.first().name, origin = origin, mode = mode, fromOutside = fromOutside)
    }

    fun openItems(
        items: List<GalleryItem>,
        title: String,
        startIndex: Int = 0,
        origin: GalleryOrigin = GalleryOrigin.Files,
        mode: ViewMode? = null,
        fromOutside: Boolean = false,
    ) {
        if (items.isEmpty()) return
        // A picture given twice is one page: the pager tells its pages apart by their keys.
        val pages = items.distinctBy { it.key }
        val start = pages.indexOfFirst { it.key == items.getOrNull(startIndex)?.key }.coerceAtLeast(0)
        _current.value = Gallery(title, pages, start, origin = origin, startMode = mode, fromOutside = fromOutside)
    }

    /** History entries as a gallery of their stored copies, opened at [index]: swiping goes through history. */
    fun openHistory(entries: List<HistoryEntry>, index: Int, title: String? = null) {
        openItems(entries.map(::historyItem), title ?: entries.getOrNull(index)?.title.orEmpty(), index, GalleryOrigin.History)
    }

    /**
     * Lists [folder]'s images in reading order and opens it at the page it was
     * left at. Returns null if it holds none.
     */
    suspend fun openFolder(folder: PlatformFile): Gallery? {
        val images = withContext(Dispatchers.IO) {
            // A name costs a query to the storage provider on Android: ask once per file, not once per comparison.
            folder.list()
                .map { it to it.name }
                .filter { (_, name) -> name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith(compareBy(NaturalOrder) { it.second })
        }
        if (images.isEmpty()) return null
        val items = images.map { (file, name) -> GalleryItem(file, name) }
        val start = progress.position(folder.toString())?.coerceIn(items.indices) ?: 0
        return Gallery(folder.name, items, start, folder).also { _current.value = it }
    }

    /** Closes the viewer. */
    fun close() {
        _current.value = null
    }

    override fun translatedKeys(target: String): Flow<Set<String>> = history.translatedKeys(target)

    @OptIn(ExperimentalTime::class)
    override suspend fun savePosition(gallery: Gallery, index: Int) {
        val folder = gallery.folder ?: return
        val item = gallery.items.getOrNull(index) ?: return
        progress.save(
            FolderProgress(
                key = folder.toString(),
                title = gallery.title,
                folder = Json.encodeToString(PlatformFileSerializer, folder),
                page = index,
                count = gallery.items.size,
                cover = Json.encodeToString(PlatformFileSerializer, item.file),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")
    }
}

/**
 * "page2" before "page10": digit runs compare by value. Manga folders are
 * numbered, and a plain string sort would put page 10 after page 1.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(startA, i).trimStart('0')
                val nb = b.substring(startB, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

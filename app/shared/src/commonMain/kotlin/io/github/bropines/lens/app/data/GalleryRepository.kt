package io.github.bropines.lens.app.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One image the viewer can page to. [key] identifies it in caches across sessions. */
class GalleryItem(val file: PlatformFile) {
    val key: String = file.toString()
    val name: String get() = file.name
}

/** What the viewer pages through: some shared or picked files, or a whole folder. */
data class Gallery(
    val title: String,
    val items: List<GalleryItem>,
    val startIndex: Int = 0,
    val folder: PlatformFile? = null,
)

@SingleIn(AppScope::class)
@Inject
class GalleryRepository {
    private val _current = MutableStateFlow<Gallery?>(null)
    val current: StateFlow<Gallery?> = _current.asStateFlow()

    fun openFiles(files: List<PlatformFile>, title: String? = null) {
        if (files.isEmpty()) return
        _current.value = Gallery(title ?: files.first().name, files.map(::GalleryItem))
    }

    /** Lists [folder]'s images in reading order. Returns null if it holds none. */
    suspend fun openFolder(folder: PlatformFile): Gallery? {
        val images = withContext(Dispatchers.IO) {
            folder.list().filter { !it.isDirectory() && it.extension.lowercase() in IMAGE_EXTENSIONS }
        }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (images.isEmpty()) return null
        return Gallery(folder.name, images.map(::GalleryItem), folder = folder).also { _current.value = it }
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

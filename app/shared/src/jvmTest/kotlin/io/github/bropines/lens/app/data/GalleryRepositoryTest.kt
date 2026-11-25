package io.github.bropines.lens.app.data

import io.github.bropines.lens.app.TestServices
import io.github.bropines.lens.app.data.history.FakeHistoryDao
import io.github.bropines.lens.app.data.history.HistoryEntry
import io.github.bropines.lens.app.data.history.HistoryRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GalleryRepositoryTest {
    private val services = TestServices("seenly-gallery")
    private val gallery = GalleryRepository(services, HistoryRepository(services, FakeHistoryDao()))

    private fun chapter(vararg names: String): File = File(services.dir, "Summer Days").apply {
        mkdirs()
        names.forEach { File(this, it).writeText(it) }
    }

    @Test
    fun aFolderOpensInReadingOrderWithOnlyItsPictures() = runTest {
        val folder = chapter("page10.png", "page2.png", "page1.png", "notes.txt", "cover.JPG")
        val opened = gallery.openFolder(PlatformFile(folder))!!

        assertEquals(listOf("cover.JPG", "page1.png", "page2.png", "page10.png"), opened.items.map { it.name })
        assertEquals("Summer Days", opened.title)
        assertEquals(GalleryOrigin.Folder, opened.origin)
        assertEquals(0, opened.startIndex)
        assertEquals(opened, gallery.current.value)
    }

    @Test
    fun aFolderWithNoPicturesOpensNothing() = runTest {
        assertNull(gallery.openFolder(PlatformFile(chapter("readme.txt"))))
        assertNull(gallery.current.value)
    }

    @Test
    fun aFolderOpensAtThePageItWasLeftAt() = runTest {
        val folder = PlatformFile(chapter("1.png", "2.png", "3.png", "4.png"))
        val first = gallery.openFolder(folder)!!
        gallery.savePosition(first, 2)

        val again = gallery.openFolder(folder)!!
        assertEquals(2, again.startIndex)
        // Two openings are two galleries, even of the same folder.
        assertNotEquals(first.id, again.id)

        val kept = gallery.progress.folders.first().single()
        assertEquals("Summer Days", kept.title)
        assertEquals(2, kept.page)
        assertEquals(4, kept.count)
        assertEquals(2f / 3f, kept.fraction)
    }

    @Test
    fun aPlacePastTheEndOfAShrunkenFolderIsItsLastPage() = runTest {
        val dir = chapter("1.png", "2.png", "3.png")
        gallery.savePosition(gallery.openFolder(PlatformFile(dir))!!, 2)
        File(dir, "3.png").delete()
        File(dir, "2.png").delete()
        assertEquals(0, gallery.openFolder(PlatformFile(dir))!!.startIndex)
    }

    @Test
    fun onlyTheLatestFoldersAreKeptNewestFirst() = runTest {
        val progress = gallery.progress
        repeat(15) { progress.save(FolderProgress("key$it", "Folder $it", "{}", page = it, count = 20)) }
        // Reading an older one again moves it to the front instead of listing it twice.
        progress.save(FolderProgress("key7", "Folder 7", "{}", page = 9, count = 20))

        val kept = progress.folders.first()
        assertEquals(12, kept.size)
        assertEquals("key7", kept.first().key)
        assertEquals(9, progress.position("key7"))
        assertEquals(1, kept.count { it.key == "key7" })
        assertNull(progress.position("key0"))

        progress.forget("key7")
        assertNull(progress.position("key7"))
    }

    @Test
    fun historyOpensAsAGalleryOfItsStoredCopiesAtTheTappedOne() {
        val entries = (1L..3L).map {
            HistoryEntry(it, "content://$it", "Shot $it", "/copies/$it.img", "/copies/$it.json", "ja", "en", "o", "t", it)
        }
        gallery.openHistory(entries, 1)

        val opened = gallery.current.value!!
        assertEquals(GalleryOrigin.History, opened.origin)
        assertEquals(1, opened.startIndex)
        assertEquals("Shot 2", opened.title)
        assertEquals(listOf(1L, 2L, 3L), opened.items.map { it.historyId })
        assertEquals("Shot 3", opened.items[2].name)

        gallery.close()
        assertNull(gallery.current.value)
    }

    @Test
    fun namesWithNumbersSortByTheNumber() {
        val sorted = listOf("ch2_p10.jpg", "ch10_p1.jpg", "ch2_p9.jpg", "Ch1_p1.jpg", "ch2_p009b.jpg").sortedWith(NaturalOrder)
        assertEquals(listOf("Ch1_p1.jpg", "ch2_p9.jpg", "ch2_p009b.jpg", "ch2_p10.jpg", "ch10_p1.jpg"), sorted)
    }
}

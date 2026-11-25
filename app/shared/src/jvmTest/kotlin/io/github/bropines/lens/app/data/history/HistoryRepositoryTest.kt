package io.github.bropines.lens.app.data.history

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.bropines.lens.app.TestServices
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.scanResult
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRepositoryTest {
    private val services = TestServices("seenly-history")
    private val dao = FakeHistoryDao()
    private val history = HistoryRepository(services, dao)
    private val english = AppSettings(targetLanguage = "en")

    private fun page(name: String, bytes: ByteArray = name.encodeToByteArray()): Pair<GalleryItem, ByteArray> {
        val file = File(services.dir, name).apply { writeBytes(bytes) }
        return GalleryItem(PlatformFile(file)) to bytes
    }

    private fun stamp(bytes: ByteArray, spec: String = "auto||lens", rotation: Int = 0) =
        ScanStamp(spec, rotation, bytes.size.toLong(), contentHash(bytes))

    @Test
    fun aPageReadBeforeComesBackOnlyForTheSameFileAskedTheSameWay() = runTest {
        val (item, bytes) = page("001.png")
        history.record(item, bytes, english, scanResult("こんにちは", "Hello"), 0, stamp(bytes), PagePlace("/manga", "Manga", 0))

        assertEquals("Hello", history.reuse(item, "en", stamp(bytes))?.translation)
        // Another language, another source setting, another turn, other pixels: each is a different scan.
        assertNull(history.reuse(item, "ru", stamp(bytes)))
        assertNull(history.reuse(item, "en", stamp(bytes, spec = "ja||lens")))
        assertNull(history.reuse(item, "en", stamp(bytes, rotation = 1)))
        assertNull(history.reuse(item, "en", stamp("other pixels".encodeToByteArray())))

        val row = dao.everything().single()
        assertEquals("/manga", row.folderKey)
        assertEquals(600, row.imageWidth)
        assertEquals(setOf(item.key), history.translatedKeys("en").first())
        assertTrue(history.translatedKeys("ru").first().isEmpty())
    }

    @Test
    fun anEntryOpenedFromHistoryIsItsOwnResultAndIsNotRecordedAgain() = runTest {
        val (item, bytes) = page("photo.jpg")
        history.record(item, bytes, english, scanResult("Bonjour", "Hello"), 0, stamp(bytes))
        val entry = dao.everything().single()

        // The stored copy, opened from history: found by its entry, whatever the source setting is now.
        val copy = GalleryItem(PlatformFile(entry.imagePath), entry.title, historyId = entry.id)
        assertTrue(history.isOwnCopy(copy))
        assertEquals("Hello", history.reuse(copy, "en", stamp(bytes, spec = "fr||lens"))?.translation)
        // In another language it has to be scanned, and that scan makes no second entry.
        assertNull(history.reuse(copy, "de", stamp(bytes)))
        history.record(copy, bytes, AppSettings(targetLanguage = "de"), scanResult("Bonjour", "Hallo"))
        assertEquals(1, dao.everything().size)
    }

    @Test
    fun aRowFromBeforeTheStampIsNeverTakenForAScan() = runTest {
        val (item, bytes) = page("old.png")
        // What version 1 wrote: no stamp at all.
        history.record(item, bytes, english, scanResult("古い", "Old"))
        assertNull(history.reuse(item, "en", stamp(bytes)))

        // Scanning it again fills the stamp in, in the same row.
        history.record(item, bytes, english, scanResult("古い", "Old"), 0, stamp(bytes))
        assertEquals(1, dao.everything().size)
        assertNotNull(history.reuse(item, "en", stamp(bytes)))
    }

    @Test
    fun aReusedFileNameReplacesThePictureAndDropsWhatWasSaidAboutTheOldOne() = runTest {
        val (item, first) = page("screen.png", "first picture".encodeToByteArray())
        history.record(item, first, english, scanResult("一", "One"), 0, stamp(first))
        history.record(item, first, AppSettings(targetLanguage = "ru"), scanResult("一", "Один"), 0, stamp(first))
        assertEquals(2, dao.everything().size)

        val second = "a second, different picture".encodeToByteArray()
        history.record(item, second, english, scanResult("二", "Two"), 0, stamp(second))

        val row = dao.everything().single()
        assertEquals("en", row.targetLanguage)
        assertContentEquals(second, File(row.imagePath).readBytes())
        assertEquals("Two", history.reuse(item, "en", stamp(second))?.translation)
        assertNull(history.reuse(item, "en", stamp(first)))
    }

    @Test
    fun deletingAnEntryKeepsThePictureAnotherLanguageStillShows() = runTest {
        val (item, bytes) = page("sign.jpg")
        history.record(item, bytes, english, scanResult("出口", "Exit"), 0, stamp(bytes))
        history.record(item, bytes, AppSettings(targetLanguage = "ru"), scanResult("出口", "Выход"), 0, stamp(bytes))
        val (en, ru) = dao.everything().sortedBy { it.targetLanguage }

        history.delete(en)
        assertTrue(File(ru.imagePath).exists())
        assertFalse(File(en.resultPath).exists())
        history.delete(ru)
        assertFalse(File(ru.imagePath).exists())
    }

    @Test
    fun contentHashTellsFilesApartAndLooksAtBothEndsOfABigOne() {
        val small = ByteArray(1000) { it.toByte() }
        assertEquals(contentHash(small), contentHash(small.copyOf()))
        assertFalse(contentHash(small) == contentHash(small.copyOf().also { it[500] = 1 }))

        val big = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
        assertFalse(contentHash(big) == contentHash(big.copyOf().also { it[10] = 99 }))
        assertFalse(contentHash(big) == contentHash(big.copyOf().also { it[big.size - 10] = 99 }))
    }

    /**
     * A database as version 1 of the app left it, built from the exported
     * schema, opens in this version with its rows intact and takes new ones.
     */
    @Test
    fun aVersionOneDatabaseOpensWithItsRows() = runTest {
        val schema = Json.parseToJsonElement(
            File("schemas/io.github.bropines.lens.app.data.history.LensDatabase/1.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val table = schema.getValue("entities").jsonArray.single().jsonObject
        val name = table.getValue("tableName").jsonPrimitive.content
        val path = File(services.dir, "lens.db").path

        BundledSQLiteDriver().open(path).let { old ->
            old.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            table.getValue("indices").jsonArray.forEach {
                old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("PRAGMA user_version = 1")
            old.execSQL(
                "INSERT INTO history (imageKey, title, imagePath, resultPath, sourceLanguage, targetLanguage, originalText, translatedText, createdAt) " +
                    "VALUES ('content://one', 'One', '/copies/one.img', '/copies/one-en.json', 'ja', 'en', 'いち', 'one', 42)",
            )
            old.close()
        }

        val db = Room.databaseBuilder<LensDatabase>(name = path).setDriver(BundledSQLiteDriver()).build()
        try {
            val old = db.history().everything().single()
            assertEquals("content://one", old.imageKey)
            assertEquals("one", old.translatedText)
            assertEquals(42L, old.createdAt)
            // What version 1 did not know is empty, not garbage.
            assertEquals("", old.scanSpec)
            assertEquals(0, old.rotation)
            assertEquals(0L, old.contentHash)
            assertNull(old.folderKey)

            db.history().insert(old.copy(id = 0, imageKey = "content://two", scanSpec = "auto||lens", folderKey = "/manga", pageIndex = 3))
            val added = db.history().find("content://two", "en")
            assertEquals("/manga", added?.folderKey)
            assertEquals(3, added?.pageIndex)
            assertEquals(listOf("content://one", "content://two"), db.history().keys("en").first().sorted())
        } finally {
            db.close()
        }
    }
}

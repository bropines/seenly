package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.app.TestServices
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Gallery
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.data.PageScanner
import io.github.bropines.lens.app.data.Scanned
import io.github.bropines.lens.app.data.SearchTarget
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.ViewerLibrary
import io.github.bropines.lens.app.data.history.PagePlace
import io.github.bropines.lens.app.scanResult
import io.github.bropines.lens.protocol.LensException
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val services = TestServices("seenly-viewer")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A scanner the test answers by hand: each page waits until it is finished or failed. */
    private class FakeScanner : PageScanner {
        val asked = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val askedFresh = mutableListOf<String>()
        val places = mutableListOf<PagePlace?>()
        private val gates = mutableMapOf<String, CompletableDeferred<Scanned>>()
        private val turns = mutableMapOf<String, Int>()

        /** When set, every scan answers at once. */
        var answer: ((GalleryItem) -> Scanned)? = null

        private fun gate(name: String) = gates.getOrPut(name) { CompletableDeferred() }
        fun finish(name: String, translation: String = "[$name]") = gate(name).complete(Scanned(scanResult(name, translation)))
        fun fail(name: String, error: Exception) = gate(name).completeExceptionally(error)

        /** Forgets [name]'s answer, so the next request for it waits again. */
        fun reset(name: String) {
            gates.remove(name)
        }

        override fun rotation(item: GalleryItem): Int = turns[item.key] ?: 0
        override fun setRotation(item: GalleryItem, turns: Int) {
            this.turns[item.key] = turns
        }

        override suspend fun scan(item: GalleryItem, settings: AppSettings, rotation: Int, fresh: Boolean, place: PagePlace?): Scanned {
            asked += item.name
            if (fresh) askedFresh += item.name
            places += place
            answer?.let { return it(item) }
            try {
                return gate(item.name).await()
            } catch (e: CancellationException) {
                stopped += item.name
                throw e
            }
        }

        override suspend fun searchSession(item: GalleryItem, settings: AppSettings, rotation: Int): SearchTarget = error("no search in these tests")
        override fun searchTextUrl(text: String): String = "https://example.test/?q=$text"
    }

    private class FakeLibrary : ViewerLibrary {
        val keys = MutableStateFlow(emptySet<String>())
        val saved = mutableListOf<Int>()
        override fun translatedKeys(target: String): Flow<Set<String>> = keys
        override suspend fun savePosition(gallery: Gallery, index: Int) {
            saved += index
        }
    }

    private fun pages(count: Int, start: Int = 0, folder: Boolean = false): Gallery {
        val dir = File(services.dir, "chapter")
        val items = (0 until count).map { GalleryItem(PlatformFile(File(dir, "p$it.png"))) }
        return Gallery("Chapter", items, start, folder = if (folder) PlatformFile(dir) else null)
    }

    private fun viewer(gallery: Gallery, scanner: FakeScanner, library: ViewerLibrary? = null, preload: Int = 2) =
        ViewerViewModel(gallery, scanner, SettingsRepository(services), library, preload)

    @Test
    fun theVisiblePageGoesFirstThenTheNextTwoThenTheOneBeforeOneAtATime() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(8, start = 3), scanner)

        vm.onPageVisible(3)
        advanceUntilIdle()
        assertEquals(listOf("p3.png"), scanner.asked)
        assertIs<PageState.Loading>(vm.pages.value[3])

        scanner.finish("p3.png")
        advanceUntilIdle()
        assertIs<PageState.Ready>(vm.pages.value[3])
        assertEquals(listOf("p3.png", "p4.png"), scanner.asked)

        scanner.finish("p4.png")
        advanceUntilIdle()
        assertEquals(listOf("p3.png", "p4.png", "p5.png"), scanner.asked)

        scanner.finish("p5.png")
        advanceUntilIdle()
        assertEquals(listOf("p3.png", "p4.png", "p5.png", "p2.png"), scanner.asked)

        scanner.finish("p2.png")
        advanceUntilIdle()
        assertEquals(4, scanner.asked.size)
        assertEquals(setOf(2, 3, 4, 5), vm.done.value)
    }

    @Test
    fun swipingToAPageAlreadyScannedAsksOnlyForWhatIsNewAhead() = runTest(dispatcher) {
        val scanner = FakeScanner().apply { answer = { Scanned(scanResult(it.name)) } }
        val vm = viewer(pages(8), scanner)

        vm.onPageVisible(0)
        advanceUntilIdle()
        assertEquals(listOf("p0.png", "p1.png", "p2.png"), scanner.asked)

        vm.onPageVisible(1)
        advanceUntilIdle()
        // Pages 1 and 2 were ready before the swipe; only page 3 is new.
        assertEquals(listOf("p0.png", "p1.png", "p2.png", "p3.png"), scanner.asked)
        assertIs<PageState.Ready>(vm.pages.value[1])
    }

    @Test
    fun aFailedPageStopsThePreloadAndRetryTakesItUpAgain() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(5), scanner)

        vm.onPageVisible(0)
        advanceUntilIdle()
        scanner.fail("p0.png", UnknownHostException("lens.google.com"))
        advanceUntilIdle()

        val failed = assertIs<PageState.Failed>(vm.pages.value[0])
        assertEquals(FailKind.Offline, failed.kind)
        assertEquals(setOf(0), vm.failed.value)
        // Offline: no point asking for the next pages too.
        assertEquals(listOf("p0.png"), scanner.asked)

        scanner.reset("p0.png")
        vm.retry(0)
        advanceUntilIdle()
        assertIs<PageState.Loading>(vm.pages.value[0])
        assertTrue(vm.failed.value.isEmpty())
        scanner.finish("p0.png")
        advanceUntilIdle()
        assertIs<PageState.Ready>(vm.pages.value[0])
        assertEquals(listOf("p0.png", "p0.png", "p1.png"), scanner.asked)
    }

    @Test
    fun aRateLimitIsToldApartFromOtherErrors() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(3), scanner, preload = 0)

        vm.onPageVisible(0)
        advanceUntilIdle()
        scanner.fail("p0.png", LensException.Http(429, "slow down"))
        advanceUntilIdle()
        assertEquals(FailKind.RateLimited, assertIs<PageState.Failed>(vm.pages.value[0]).kind)

        vm.onPageVisible(1)
        advanceUntilIdle()
        scanner.fail("p1.png", LensException.Http(500, "oops"))
        advanceUntilIdle()
        assertEquals(FailKind.Other, assertIs<PageState.Failed>(vm.pages.value[1]).kind)
    }

    @Test
    fun onlyThePagesAroundTheReaderAreKept() = runTest(dispatcher) {
        val scanner = FakeScanner().apply { answer = { Scanned(scanResult(it.name)) } }
        val vm = viewer(pages(40), scanner)

        for (page in 0 until 30) {
            vm.onPageVisible(page)
            advanceUntilIdle()
        }
        // Two behind, the page itself and the two scanned ahead.
        assertEquals((27..31).toSet(), vm.pages.value.keys)
        // What was read is still known as read, for the grid's marks.
        assertTrue(vm.done.value.containsAll((0..31).toList()))
    }

    @Test
    fun jumpingAwayStopsTheScanLeftBehind() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(30), scanner)

        vm.onPageVisible(0)
        advanceUntilIdle()
        vm.onPageVisible(20)
        advanceUntilIdle()

        assertEquals(listOf("p0.png"), scanner.stopped)
        assertNull(vm.pages.value[0])
        assertEquals(listOf("p0.png", "p20.png"), scanner.asked)
    }

    @Test
    fun rescanAsksAnewAndKeepsTheOldTranslationUntilTheNewOneLands() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(2), scanner, preload = 0)

        vm.onPageVisible(0)
        advanceUntilIdle()
        scanner.finish("p0.png", "first try")
        advanceUntilIdle()

        scanner.reset("p0.png")
        vm.rescan(0)
        advanceUntilIdle()
        assertEquals(listOf("p0.png"), scanner.askedFresh)
        assertEquals("first try", assertIs<PageState.Ready>(vm.pages.value[0]).result.translation)
        assertEquals(setOf(0), vm.stale.value)

        scanner.finish("p0.png", "second try")
        advanceUntilIdle()
        assertEquals("second try", assertIs<PageState.Ready>(vm.pages.value[0]).result.translation)
        assertTrue(vm.stale.value.isEmpty())
    }

    @Test
    fun turningAPageDropsItsResultAndReadsItAgain() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val gallery = pages(2)
        val vm = viewer(gallery, scanner, preload = 0)

        vm.onPageVisible(0)
        advanceUntilIdle()
        scanner.finish("p0.png")
        advanceUntilIdle()

        scanner.reset("p0.png")
        vm.rotate(0)
        advanceUntilIdle()
        assertEquals(1, vm.rotations.value[gallery.items[0].key])
        assertIs<PageState.Loading>(vm.pages.value[0])
        assertEquals(listOf("p0.png", "p0.png"), scanner.asked)
    }

    @Test
    fun aFolderRemembersThePageAndTellsHistoryWhereItSits() = runTest(dispatcher) {
        val scanner = FakeScanner().apply { answer = { Scanned(scanResult(it.name)) } }
        val library = FakeLibrary()
        val gallery = pages(6, folder = true)
        val vm = viewer(gallery, scanner, library, preload = 1)

        vm.onPageVisible(0)
        advanceUntilIdle()
        vm.onPageVisible(1)
        advanceUntilIdle()

        assertEquals(listOf(0, 1), library.saved)
        assertEquals(PagePlace(gallery.folderKey!!, "Chapter", 0), scanner.places.first())
        assertEquals(listOf(0, 1, 2), scanner.places.map { it?.pageIndex })
    }

    @Test
    fun picturesOutsideAFolderHaveNoPlace() = runTest(dispatcher) {
        val scanner = FakeScanner().apply { answer = { Scanned(scanResult(it.name)) } }
        val vm = viewer(pages(2), scanner, preload = 0)
        vm.onPageVisible(0)
        advanceUntilIdle()
        assertEquals(listOf<PagePlace?>(null), scanner.places)
    }

    @Test
    fun aPageOutOfRangeIsIgnored() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val vm = viewer(pages(2), scanner)
        vm.onPageVisible(5)
        vm.retry(-1)
        vm.rescan(9)
        advanceUntilIdle()
        assertTrue(scanner.asked.isEmpty())
    }
}

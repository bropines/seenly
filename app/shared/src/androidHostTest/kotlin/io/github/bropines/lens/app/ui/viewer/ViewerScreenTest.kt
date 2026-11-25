package io.github.bropines.lens.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.readme.Demo
import io.github.bropines.lens.app.ui.ScreenTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenTest : ScreenTest() {
    private var closed = false

    private fun open(items: List<GalleryItem>, title: String = "Summer Days", start: Int = 0) {
        graph.gallery.openItems(items, title, start)
        val gallery = graph.gallery.current.value!!
        val vm = ViewerViewModel(gallery, graph.scans, graph.settings, graph.gallery)
        show { ViewerScreen(vm, graph.services, graph.translator, onBack = { closed = true }, onTranslateText = {}) }
        settle()
    }

    private val middle get() = compose.onRoot().fetchSemanticsNode().size.let { Offset(it.width / 2f, it.height * 0.4f) }

    @Test
    fun aChapterShowsItsPagesAsAStripAndSaysWhichOneIsInFront() {
        open(chapter(6), start = 2)
        compose.onNodeWithText("3 / 6").assertIsDisplayed()
        // The strip's thumbnails are named by their page number.
        compose.onNodeWithContentDescription("3").assertIsDisplayed()
        compose.onNodeWithContentDescription("All pages").assertIsDisplayed()
        snap("viewer-chapter")
    }

    @Test
    fun pullingAPageDownPutsItAwayIntoTheGridAndATapBringsAnotherOut() {
        open(chapter(6))
        val from = middle
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(from)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(4f, 42f))
            }
        }
        // The grid behind is made at this moment: give its thumbnails the time to be decoded.
        repeat(8) {
            Thread.sleep(40)
            step(16)
        }
        // Half way: the page follows the finger, smaller, and the grid it goes back to shows behind it.
        snap("viewer-pull")
        compose.onRoot().performTouchInput { up() }
        step(120)
        snap("viewer-pull-landing")
        compose.mainClock.autoAdvance = true
        settle(8)
        compose.onNodeWithText("6 images · 6 translated").assertIsDisplayed()
        assertFalse(closed)
        snap("viewer-grid")

        // Out of the grid again: page 4 flies out of its cell.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("4").performClick()
        step(140)
        snap("viewer-fly-out")
        compose.mainClock.autoAdvance = true
        settle(8)
        compose.onNodeWithText("4 / 6").assertIsDisplayed()
        snap("viewer-after-grid")
    }

    @Test
    fun aShortPullLetsThePageSpringBack() {
        open(chapter(3))
        val from = middle
        compose.onRoot().performTouchInput {
            down(from)
            repeat(6) {
                advanceEventTime(40)
                moveBy(Offset(0f, 14f))
            }
            advanceEventTime(400)
            up()
        }
        settle(8)
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        assertFalse(closed)
    }

    @Test
    fun aSinglePicturePulledDownClosesTheViewer() {
        open(listOf(page("menu.jpg", "IMG_0001.jpg")), title = "Camera")
        val from = middle
        compose.onRoot().performTouchInput {
            down(from)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(0f, 45f))
            }
            up()
        }
        settle(8)
        assertTrue(closed)
        assertNull(heroes.shown)
    }

    @Test
    fun theGridButtonAndBackLeaveTheWayTheyShould() {
        open(chapter(4))
        compose.onNodeWithContentDescription("All pages").performClick()
        settle(8)
        compose.onNodeWithText("4 images · 4 translated").assertIsDisplayed()
        // Closing from the grid closes the viewer.
        compose.onNodeWithContentDescription("Close").performClick()
        settle(8)
        assertTrue(closed)
    }

    @Test
    fun holdingAPageOpensTheQuickActions() {
        open(chapter(2))
        val at = middle
        compose.onRoot().performTouchInput { longClick(at) }
        settle(4)
        compose.onNodeWithText("Copy translation").assertIsDisplayed()
        compose.onNodeWithText("Translate again").assertIsDisplayed()
        snap("viewer-quick-menu")
        compose.onNodeWithText("Share…").performClick()
        settle(6)
        compose.onNodeWithText("Image and text").assertIsDisplayed()
        snap("viewer-share")
    }

    @Test
    fun theEyeHidesTheTranslationOnATapAndPeeksWhileHeld() {
        open(chapter(2))
        compose.onNodeWithContentDescription("Hide translation").performClick()
        settle(3)
        compose.onNodeWithContentDescription("Show translation").assertIsDisplayed()
        snap("viewer-original")
        compose.onNodeWithContentDescription("Show translation").performClick()
        settle(3)
        // Held: the original for as long as the finger is down, the translation again after.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Hide translation").performTouchInput { down(center) }
        step(600)
        snap("viewer-peek")
        compose.onNodeWithContentDescription("Hide translation").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        settle(3)
        compose.onNodeWithContentDescription("Hide translation").assertIsDisplayed()
    }

    @Test
    fun withTheControlsAwayATurnedPageSaysItsNumber() {
        open(chapter(5))
        val at = middle
        // A tap beside the picture, in the dark above it and below the bar, puts the controls away.
        val root = compose.onRoot().fetchSemanticsNode().size
        val picture = android.graphics.BitmapFactory.decodeFile(Demo.image("manga.png").path)
        val above = (root.height - picture.height * (root.width.toFloat() / picture.width)) / 2
        assertTrue("the demo page leaves no margin to tap", above > 300f)
        compose.onRoot().performTouchInput { click(Offset(at.x, above - 40f)) }
        settle(4)
        compose.onRoot().performTouchInput {
            down(Offset(at.x * 1.6f, at.y))
            repeat(8) {
                advanceEventTime(16)
                moveBy(Offset(-90f, 0f))
            }
            up()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
        snap("viewer-bare")
    }

    @Test
    fun aViewModelKeepsOnlyTheNeighbourhoodWhileTheStripRunsThroughAChapter() {
        val items = chapter(12)
        graph.gallery.openItems(items, "Summer Days")
        val vm = ViewerViewModel(graph.gallery.current.value!!, graph.scans, graph.settings, graph.gallery)
        show { ViewerScreen(vm, graph.services, graph.translator, onBack = {}, onTranslateText = {}) }
        settle()
        compose.onNodeWithContentDescription("3").performClick()
        settle(10)
        compose.onNodeWithText("3 / 12").assertIsDisplayed()
        assertNotNull(vm.pages.value[2])
        assertEquals(setOf(0, 1, 2, 3, 4), vm.pages.value.keys)
    }
}

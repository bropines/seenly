package io.github.bropines.lens.app.ui

import androidx.compose.runtime.Composable
import io.github.bropines.lens.app.readme.Demo
import io.github.bropines.lens.app.ui.main.MainScreen
import io.github.bropines.lens.app.ui.main.Tab
import io.github.bropines.lens.app.ui.settings.TranslatorsScreen
import io.github.bropines.lens.app.ui.viewer.ViewerScreen
import io.github.bropines.lens.app.ui.viewer.ViewerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

/*
 * Every main screen with the phone turned on its side (891 x 411 dp), and on a tablet held
 * the long way: pictures in build/viewer-shots/land-*.png, to see what a layout change did.
 */

internal const val LANDSCAPE = "en-w891dp-h411dp-land-night-420dpi"
internal const val TABLET = "en-w1280dp-h800dp-land-night-240dpi"

@Config(sdk = [35], qualifiers = LANDSCAPE)
class LandscapeShots : TurnedShots("land")

@Config(sdk = [35], qualifiers = TABLET)
class TabletShots : TurnedShots("tablet")

abstract class TurnedShots(private val tag: String) : ScreenTest() {
    @Composable
    private fun Main(tab: Tab) = MainScreen(
        graph = graph,
        settings = settings,
        tab = tab,
        onTab = {},
        onOpenViewer = {},
        onOpenCamera = {},
        onOpenHistory = {},
    )

    private fun shot(name: String) {
        settle()
        snap("$tag-$name")
    }

    @Test
    fun home() {
        chapter(3)
        show { Main(Tab.Images) }
        shot("home")
    }

    @Test
    fun history() {
        chapter(3)
        show { Main(Tab.History) }
        shot("history")
    }

    @Test
    fun text() {
        show { Main(Tab.Text) }
        shot("text")
    }

    @Test
    fun settings() {
        show { Main(Tab.Settings) }
        shot("settings")
    }

    @Test
    fun translators() {
        show { TranslatorsScreen(graph.settings, settings, graph.translator, onBack = {}) }
        shot("translators")
    }

    @Test
    fun viewer() {
        val pages = chapter(3)
        runBlocking { graph.openPrimed("Summer Days", pages, Demo.scan("manga.png", "en"), settings) }
        val vm = ViewerViewModel(graph.gallery.current.value!!, graph.scans, graph.settings)
        show { ViewerScreen(vm, graph.services, graph.translator, onBack = {}, onTranslateText = {}) }
        shot("viewer")
    }
}

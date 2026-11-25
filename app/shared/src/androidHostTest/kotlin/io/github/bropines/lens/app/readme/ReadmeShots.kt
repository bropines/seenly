package io.github.bropines.lens.app.readme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.tab_text
import io.github.bropines.lens.app.ui.main.MainScreen
import io.github.bropines.lens.app.ui.main.Tab
import io.github.bropines.lens.app.ui.settings.TranslatorsScreen
import io.github.bropines.lens.app.ui.viewer.ViewerScreen
import io.github.bropines.lens.app.ui.viewer.ViewerViewModel
import io.github.bropines.lens.protocol.ScanResult
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.Locale

/*
 * The screenshots in the README: the real screens, fed the made-up data in
 * Showcase.kt, rendered by Robolectric on the build machine at a phone's size
 * (411 x 891 dp at 420 dpi) in English and in Russian. They land in
 * build/readme-shots; scripts/readme_shots.py frames them for docs/screenshots.
 *
 *   ./gradlew :app:shared:readmeShots
 *
 * Robolectric rather than the layoutlib preview renderer: it runs the real
 * activity, Compose Multiplatform resources and image decoding, so a viewer
 * page shows the picture with its translation drawn by lens-overlay.
 */

private const val PHONE = "w411dp-h891dp-port-night-420dpi"

@Config(sdk = [35], qualifiers = "en-$PHONE")
class ReadmeShotsEn : ReadmeShots("en")

@Config(sdk = [35], qualifiers = "ru-$PHONE")
class ReadmeShotsRu : ReadmeShots("ru")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ReadmeShots(private val lang: String) {
    @get:Rule
    val compose = createComposeRule()

    private val graph = DemoGraph.create()
    private val settings = Demo.settings(lang)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag(lang))
        runBlocking { graph.settings.update { settings } }
    }

    @Test
    fun home() {
        compose.setContent { Shot { Main(Tab.Images) } }
        snap("Home")
    }

    @Test
    fun settings() {
        compose.setContent { Shot { Main(Tab.Settings) } }
        snap("Settings")
    }

    @Test
    fun translators() {
        compose.setContent { Shot { TranslatorsScreen(graph.settings, settings, graph.translator, onBack = {}) } }
        snap("Translators")
    }

    @Test
    fun viewerManga() {
        // A chapter of a few pages: the counter reads "1 / 3", like a real folder.
        val pages = (1..3).map { page(Demo.image("manga.png"), "summer-days-%02d.png".format(it)) }
        viewer("Summer Days", pages, Demo.scan("manga.png", lang))
        snap("ViewerManga")
    }

    @Test
    fun viewerSign() {
        viewer("Camera", listOf(page(Demo.image("menu.jpg"), "IMG_20260712_1931.jpg")), Demo.scan("menu.jpg", lang))
        snap("ViewerSign")
    }

    @Test
    fun viewerText() {
        val scan = Demo.scan("notice.jpg", lang)
        viewer("Camera", listOf(page(Demo.image("notice.jpg"), "IMG_20260803_0915.jpg")), scan)
        compose.onNodeWithText(text(Res.string.tab_text)).performClick()
        settle()
        // Press on the first word of the second paragraph and drag to its last, as a finger would.
        val words = scan.paragraphs[1].lines.flatMap { it.words }
        val root = compose.onRoot().fetchSemanticsNode().size
        val image = Demo.image("notice.jpg").let { android.graphics.BitmapFactory.decodeFile(it.path) }
        val scale = minOf(root.width.toFloat() / image.width, root.height.toFloat() / image.height)
        val left = (root.width - image.width * scale) / 2
        val top = (root.height - image.height * scale) / 2
        fun at(x: Float, y: Float) = Offset(left + x * image.width * scale, top + y * image.height * scale)
        val first = words.first().box!!
        val last = words.last().box!!
        val start = at(first.centerX, first.centerY)
        val end = at(last.right - last.width * 0.1f, last.centerY)
        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(800)
            for (step in 1..12) {
                advanceEventTime(30)
                moveTo(start + (end - start) * (step / 12f))
            }
            up()
        }
        snap("ViewerText")
    }

    @androidx.compose.runtime.Composable
    private fun Main(tab: Tab) = MainScreen(
        graph = graph,
        settings = settings,
        tab = tab,
        onTab = {},
        onOpenViewer = {},
        onOpenCamera = {},
        onOpenHistory = {},
    )

    private fun viewer(title: String, items: List<GalleryItem>, result: ScanResult) {
        runBlocking { graph.openPrimed(title, items, result, settings) }
        val vm = ViewerViewModel(graph.gallery.current.value!!, graph.scans, graph.settings)
        compose.setContent {
            Shot { ViewerScreen(vm, graph.services, graph.translator, onBack = {}, onTranslateText = {}) }
        }
    }

    /** A copy of [source] under [name], so each page has its own file and title. */
    private fun page(source: File, name: String): GalleryItem {
        val dir = File(graph.services.dataDir, "pictures").apply { mkdirs() }
        val file = File(dir, name)
        if (!file.exists()) Files.copy(source.toPath(), file.toPath())
        return GalleryItem(PlatformFile(file.path))
    }

    private fun text(resource: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(resource) }

    /** Lets images decode on their background threads and the screen come to rest. */
    private fun settle() {
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    private fun snap(name: String) {
        settle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/readme-shots").apply { mkdirs() }.resolve("${name}_$lang.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

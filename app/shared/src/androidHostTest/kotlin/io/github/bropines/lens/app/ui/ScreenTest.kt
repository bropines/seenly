package io.github.bropines.lens.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.data.history.PagePlace
import io.github.bropines.lens.app.data.history.ScanStamp
import io.github.bropines.lens.app.data.history.contentHash
import io.github.bropines.lens.app.readme.Demo
import io.github.bropines.lens.app.readme.DemoGraph
import io.github.bropines.lens.app.readme.Shot
import io.github.bropines.lens.app.ui.gallery.HeroRegistry
import io.github.bropines.lens.app.ui.gallery.LocalHeroRegistry
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.Locale

/*
 * The image screens driven on the build machine: the real composables over
 * the made-up world of the README renders (readme/Showcase.kt), with touches
 * sent the way a finger would. Each test also leaves pictures of what it saw
 * in build/viewer-shots, to look at what a change did without a phone.
 */

internal const val PHONE = "en-w411dp-h891dp-port-night-420dpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
abstract class ScreenTest {
    @get:Rule
    val compose = createComposeRule()

    protected val graph = DemoGraph.create()
    protected val settings: AppSettings = Demo.settings("en")
    protected val heroes = HeroRegistry()

    @Before
    fun setUpWorld() {
        Locale.setDefault(Locale.ENGLISH)
        runBlocking { graph.settings.update { settings } }
    }

    /** [content] in the app's theme, with thumbnails that pictures can fly between. */
    protected fun show(content: @Composable () -> Unit) {
        compose.setContent {
            Shot { CompositionLocalProvider(LocalHeroRegistry provides heroes) { content() } }
        }
    }

    /** A copy of the demo picture [source] under [name], so every page is a file of its own. */
    protected fun page(source: String, name: String): GalleryItem {
        val dir = File(graph.services.dataDir, "pictures").apply { mkdirs() }
        val file = File(dir, name)
        if (!file.exists()) Files.copy(Demo.image(source).toPath(), file.toPath())
        return GalleryItem(PlatformFile(file.path))
    }

    /** A chapter of [count] pages, each known to the scanner already so nothing is asked of the network. */
    protected fun chapter(count: Int, title: String = "Summer Days", read: Int = count): List<GalleryItem> {
        val pages = (1..count).map { page("manga.png", "summer-days-%02d.png".format(it)) }
        val scan = Demo.scan("manga.png", "en")
        val bytes = Demo.image("manga.png").readBytes()
        runBlocking {
            pages.forEachIndexed { index, item ->
                graph.scans.prime(item, settings, scan)
                graph.scans.prime(item, AppSettings(), scan)
                // The first [read] pages were read in an earlier session: history has them.
                if (index < read) {
                    val stamp = ScanStamp("auto||lens", 0, bytes.size.toLong(), contentHash(bytes))
                    graph.history.record(item, bytes, settings, scan, 0, stamp, PagePlace("/demo/$title", title, index))
                }
            }
        }
        return pages
    }

    /** Lets images decode on their background threads and the screen come to rest. */
    protected fun settle(rounds: Int = 20) {
        repeat(rounds) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(40)
            compose.waitForIdle()
        }
    }

    /** Moves the clock on by [millis] without waiting for anything, to look at a movement half way. */
    protected fun step(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
    }

    /** Saves what the screen shows as build/viewer-shots/[name].png; a second window (a menu, a sheet) as [name]-2.png. */
    protected fun snap(name: String) {
        val out = File("build/viewer-shots").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        for (i in 0 until count) {
            val bitmap = roots[i].captureToImage().asAndroidBitmap()
            val file = out.resolve(if (i == 0) "$name.png" else "$name-${i + 1}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

package io.github.bropines.lens.overlay

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.BeforeTest

/**
 * [RenderContract] against Skia, the desktop backend.
 *
 * Skia's Linux build links against libGL, libX11 and libfontconfig even to
 * draw into memory. On a machine without them (a bare container, a headless
 * WSL distro) the library does not load, and these tests are skipped rather
 * than failed.
 */
@OptIn(InternalComposeUiApi::class)
class SkiaRenderTest : RenderContract() {
    @BeforeTest
    fun requireSkia() {
        assumeTrue("Skia's native library does not load on this machine", skiaLoads)
    }

    // A window registers the Skia text backend when it opens; a test has none.
    override val fonts: FontFamily.Resolver by lazy {
        registerSkikoComposeImplementation()
        createFontFamilyResolver()
    }

    override val hasFonts: Boolean by lazy { FontMgr.default.familiesCount > 0 }

    override fun ImageBitmap.encode(format: TestFormat): ByteArray {
        val skiaFormat = when (format) {
            TestFormat.Png -> EncodedImageFormat.PNG
            TestFormat.Jpeg -> EncodedImageFormat.JPEG
            TestFormat.Webp -> EncodedImageFormat.WEBP
        }
        return Image.makeFromBitmap(asSkiaBitmap()).use { it.encodeToData(skiaFormat, 100)!!.bytes }
    }

    override fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    override fun saveSheet(name: String, png: ByteArray) {
        File("build/overlay-gallery/skia").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    private companion object {
        val skiaLoads: Boolean by lazy {
            try {
                Bitmap().close()
                true
            } catch (e: Throwable) {
                false
            }
        }
    }
}

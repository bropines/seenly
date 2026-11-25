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
import java.io.File

/**
 * Skia, the desktop backend. Its Linux build needs libGL, libX11 and
 * libfontconfig even to draw into memory, and text needs a font: on a machine
 * without them the backend is not [available] and its tests do nothing.
 */
@OptIn(InternalComposeUiApi::class)
internal object SkiaBackend : RenderBackend {
    override val name: String = "skia"

    // A window registers the Skia text backend when it opens; a test has none.
    override val fonts: FontFamily.Resolver by lazy {
        registerSkikoComposeImplementation()
        createFontFamilyResolver()
    }

    override val available: Boolean by lazy {
        try {
            Bitmap().close()
            FontMgr.default.familiesCount > 0
        } catch (e: Throwable) {
            false
        }
    }

    override fun encodePng(image: ImageBitmap): ByteArray =
        Image.makeFromBitmap(image.asSkiaBitmap()).use { it.encodeToData(EncodedImageFormat.PNG, 100)!!.bytes }

    override fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    override fun readFixture(name: String): ByteArray? =
        File("src/commonTest/resources/fixtures/$name").takeIf { it.isFile }?.readBytes()

    override fun save(directory: String, file: String, bytes: ByteArray) {
        File("build/$directory/$name").apply { mkdirs() }.resolve(file).writeBytes(bytes)
    }
}

/** [QualityContract] against Skia. */
class SkiaQualityTest : QualityContract() {
    override val backend: RenderBackend = SkiaBackend
}

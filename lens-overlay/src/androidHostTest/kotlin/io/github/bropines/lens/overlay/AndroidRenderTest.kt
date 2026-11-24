package io.github.bropines.lens.overlay

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [RenderContract] against Android's own graphics stack.
 *
 * Robolectric's native graphics mode runs the real `Bitmap`, `BitmapFactory`,
 * `Canvas` and text layout on the host, with the platform's fonts, so this is
 * the Android actuals and the Android text rendering under test, not a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidRenderTest : RenderContract() {
    override val fonts: FontFamily.Resolver by lazy { createFontFamilyResolver(RuntimeEnvironment.getApplication()) }

    override val hasFonts: Boolean = true

    override fun ImageBitmap.encode(format: TestFormat): ByteArray {
        val androidFormat = when (format) {
            TestFormat.Png -> Bitmap.CompressFormat.PNG
            TestFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            TestFormat.Webp -> Bitmap.CompressFormat.WEBP_LOSSLESS
        }
        return ByteArrayOutputStream().also { asAndroidBitmap().compress(androidFormat, 100, it) }.toByteArray()
    }

    override fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    override fun readFixture(name: String): ByteArray = File("src/commonTest/resources/fixtures/$name").readBytes()

    override fun saveSheet(name: String, png: ByteArray) {
        File("build/overlay-gallery/android").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }
}

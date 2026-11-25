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

/** Android's own graphics stack and fonts, on the host: what a phone draws. */
internal object AndroidBackend : RenderBackend {
    override val name: String = "android"

    override val fonts: FontFamily.Resolver by lazy { createFontFamilyResolver(RuntimeEnvironment.getApplication()) }

    override val available: Boolean = true

    override fun encodePng(image: ImageBitmap): ByteArray =
        ByteArrayOutputStream().also { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    override fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    override fun readFixture(name: String): ByteArray? =
        File("src/commonTest/resources/fixtures/$name").takeIf { it.isFile }?.readBytes()

    override fun save(directory: String, file: String, bytes: ByteArray) {
        File("build/$directory/$name").apply { mkdirs() }.resolve(file).writeBytes(bytes)
    }
}

/** [QualityContract] against Android's text layout and canvas, through Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidQualityTest : QualityContract() {
    override val backend: RenderBackend = AndroidBackend
}

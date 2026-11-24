package io.github.bropines.lens.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.history.LensDatabase
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.ui.theme.LocalDynamicColor
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.TranslatedImage
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.Locale

/*
 * The README screenshot of the screen translator: its menu over a manga page
 * it has just translated in place, as it looks above another app. The page
 * and its Lens results are the shared demo data in
 * app/shared/src/androidHostTest/resources/readme (see ReadmeShots there).
 *
 *   ./gradlew readmeShots
 */

private const val PHONE = "w411dp-h891dp-port-night-420dpi"

@Config(sdk = [35], qualifiers = "en-$PHONE", application = android.app.Application::class)
class BubbleMenuShotsEn : BubbleMenuShots("en")

@Config(sdk = [35], qualifiers = "ru-$PHONE", application = android.app.Application::class)
class BubbleMenuShotsRu : BubbleMenuShots("ru")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class BubbleMenuShots(private val lang: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** The app's manifest has no plain ComponentActivity for the test rule to start; Robolectric is told of one. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }.around(compose)

    private val demo = File("../shared/src/androidHostTest/resources/readme")

    @Test
    fun bubbleMenu() {
        Locale.setDefault(Locale.forLanguageTag(lang))
        val settings = SettingsRepository(ScratchServices(Files.createTempDirectory("seenly-bubble").toString()))
        runBlocking { settings.update { AppSettings(targetLanguage = lang) } }
        val page = BitmapFactory.decodeFile(File(demo, "manga.png").path).asImageBitmap()
        val scan = Json { ignoreUnknownKeys = true }
            .decodeFromString(ScanResult.serializer(), File(demo, "manga.$lang.json").readText())

        // The app theme gives a plain activity a title bar; the menu window has none.
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDynamicColor provides false) {
                // Another app's page, already translated in place, dimmed behind the menu as the window does.
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TranslatedImage(page, scan, OverlayMode.Translated, OverlayStyle.Manga, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                    MenuSheet(
                        settings = settings,
                        mode = ScreenMode.Screen,
                        bottomInset = 24.dp,
                        onMode = {},
                        onPrimary = {},
                        onLanguage = { _, _ -> },
                        onStop = {},
                        onDismiss = {},
                    )
                }
            }
        }
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            compose.waitForIdle()
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/readme-shots").apply { mkdirs() }.resolve("BubbleMenu_$lang.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Only the settings file is ever touched. */
private class ScratchServices(override val dataDir: String) : PlatformServices {
    override fun copyText(text: String) {}
    override fun shareText(text: String) {}
    override fun shareImage(bytes: ByteArray, fileName: String) {}
    override fun openUrl(url: String) {}
    override fun databaseBuilder(): androidx.room.RoomDatabase.Builder<LensDatabase> = error("not used")
}

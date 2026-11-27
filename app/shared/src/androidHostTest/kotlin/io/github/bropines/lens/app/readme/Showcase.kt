package io.github.bropines.lens.app.readme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.compose.setSingletonImageLoaderFactory
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.data.GalleryRepository
import io.github.bropines.lens.app.data.ScanRepository
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.TextTranslator
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.data.TranslatorPrefs
import io.github.bropines.lens.app.data.history.FakeHistoryDao
import io.github.bropines.lens.app.data.history.HistoryRepository
import io.github.bropines.lens.app.data.history.LensDatabase
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.screen.ScreenTranslate
import io.github.bropines.lens.app.ui.theme.LensTheme
import io.github.bropines.lens.app.ui.theme.LocalDynamicColor
import io.github.bropines.lens.protocol.LensClient
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.translate.TranslationPipeline
import io.github.bropines.lens.translate.TranslatorRegistry
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.PlatformFileSerializer
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/*
 * The made-up world the README screenshots show: three pictures drawn by
 * scripts/demo_images.py, the answers Lens gave for them (saved once with the
 * CLI, in English and in Russian), and a few settings. Nothing here is a real
 * person's data, and nothing goes to the network: every request fails.
 */

/** The pictures and their saved Lens results, in src/androidHostTest/resources/readme. */
object Demo {
    private val dir = File("src/androidHostTest/resources/readme")
    private val json = Json { ignoreUnknownKeys = true }

    fun image(name: String): File = File(dir, name).also { require(it.exists()) { "missing $it; run scripts/demo_images.py" } }

    /** What Lens returned for [image] ("manga.png") translated into [lang]. */
    fun scan(image: String, lang: String): ScanResult =
        json.decodeFromString(ScanResult.serializer(), File(dir, "${image.substringBeforeLast('.')}.$lang.json").readText())

    /** The defaults, translating into the screenshot's language, with a couple of engines chosen. */
    fun settings(lang: String) = AppSettings(
        targetLanguage = lang,
        lastFolder = Json.encodeToString(PlatformFileSerializer, PlatformFile("/storage/emulated/0/Manga/Summer Days")),
        translators = TranslatorPrefs(enabled = listOf("google", "deepl-web", "yandex")),
    )
}

/** The app's services on a scratch directory; nothing here is ever asked to do real work. */
class DemoServices(override val dataDir: String) : PlatformServices {
    override fun copyText(text: String) {}
    override fun shareText(text: String) {}
    override fun shareImage(bytes: ByteArray, fileName: String) {}
    override fun openUrl(url: String) {}
    override fun databaseBuilder(): androidx.room.RoomDatabase.Builder<LensDatabase> = error("the README renders open no database")
    override val screenTranslate: ScreenTranslate = object : ScreenTranslate {
        override val running: StateFlow<Boolean> = MutableStateFlow(false)
        override val opaque: StateFlow<Boolean> = MutableStateFlow(true)
        override fun start() {}
        override fun stop() {}
        override fun openOpaqueSettings() {}
    }
}

/**
 * The real repositories over [DemoServices], wired by hand instead of by
 * Metro: settings in a scratch DataStore, Lens and the translators behind an
 * HTTP client that refuses every request.
 */
class DemoGraph(override val services: DemoServices) : AppGraph {
    private val offline = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
    private val registry = TranslatorRegistry()

    override val settings = SettingsRepository(services)
    override val lens = LensClient(offline)

    /** History over a table in memory: the renders cannot open the database, and need not. */
    override val history: HistoryRepository = HistoryRepository(services, FakeHistoryDao())
    override val gallery = GalleryRepository(services, history)
    override val scans = ScanRepository(lens, history, services)
    override val updates = io.github.bropines.lens.app.update.UpdateController(offline, settings, services)
    override val account by lazy { io.github.bropines.lens.app.data.GoogleAccount(services, settings, network) }

    /** Built like the app's, and never asked for a connection: everything above is handed [offline]. */
    override val network = io.github.bropines.lens.app.net.AppNetwork(services, settings)
    override val translator = TextTranslator(
        pipeline = TranslationPipeline(registry, offline, settings = { settings.current.value.translators.toSettings() }),
        registry = registry,
        settings = settings,
        http = offline,
    )

    /** Opens [items] in the viewer with [result] already known for each, as history does. */
    suspend fun openPrimed(title: String, items: List<GalleryItem>, result: ScanResult, settings: AppSettings) {
        for (item in items) {
            scans.prime(item, settings, result)
            // The viewer starts on the default settings until the DataStore answers.
            scans.prime(item, AppSettings(), result)
        }
        gallery.openItems(items, title)
    }

    companion object {
        fun create(): DemoGraph = DemoGraph(DemoServices(Files.createTempDirectory("seenly-readme").toString()))
    }
}


/** The app's theme as the screenshots show it: dark, in Seenly's own colours rather than a wallpaper's. */
@Composable
fun Shot(content: @Composable () -> Unit) {
    setSingletonImageLoaderFactory { context ->
        // BitmapFactory first: Robolectric cannot run ImageDecoder, which Coil prefers on API 29+.
        ImageLoader.Builder(context).components { add(BitmapFactoryDecoder.Factory()); addPlatformFileSupport() }.build()
    }
    CompositionLocalProvider(LocalDynamicColor provides false) {
        LensTheme(ThemeMode.Dark, content = content)
    }
}

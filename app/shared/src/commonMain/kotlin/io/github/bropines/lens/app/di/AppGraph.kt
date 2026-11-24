package io.github.bropines.lens.app.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.github.bropines.lens.app.data.GalleryRepository
import io.github.bropines.lens.app.data.GoogleAccount
import io.github.bropines.lens.app.ondevice.OnDeviceProvider
import io.github.bropines.lens.app.update.UpdateController
import io.github.bropines.lens.app.data.ScanRepository
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.data.PreferencesKeyValueStore
import io.github.bropines.lens.app.data.TextTranslator
import io.github.bropines.lens.translate.TranslationPipeline
import io.github.bropines.lens.translate.TranslatorRegistry
import io.github.bropines.lens.protocol.LensClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** The app's object graph, checked at compile time by Metro. */
@DependencyGraph(AppScope::class)
interface AppGraph {
    val services: PlatformServices
    val settings: SettingsRepository
    val gallery: GalleryRepository
    val scans: ScanRepository
    /** For callers that scan frames rather than gallery files (the screen translator). */
    val lens: LensClient
    val translator: TextTranslator
    val history: io.github.bropines.lens.app.data.history.HistoryRepository
    val updates: UpdateController
    val account: GoogleAccount

    /** One HTTP client for the app; the engine (OkHttp) comes from the classpath. */
    @SingleIn(AppScope::class)
    @Provides
    fun httpClient(): HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // Engines set their own, shorter, timeouts; an LLM may take up to two minutes.
            requestTimeoutMillis = 130_000
        }
    }

    /** Anonymous unless the user connected a Google account and left "use for Lens" on. */
    @SingleIn(AppScope::class)
    @Provides
    fun lensClient(http: HttpClient, account: GoogleAccount): LensClient = LensClient(http, credentials = account)

    /** The built-in engines, plus the on-device one where the build has it. */
    @SingleIn(AppScope::class)
    @Provides
    fun translatorRegistry(services: PlatformServices, settings: SettingsRepository): TranslatorRegistry = TranslatorRegistry().apply {
        services.onDevice?.let { ml -> register(OnDeviceProvider(ml) { settings.current.value.onDevice.wifiOnly }) }
    }

    @SingleIn(AppScope::class)
    @Provides
    fun textTranslator(
        registry: TranslatorRegistry,
        http: HttpClient,
        settings: SettingsRepository,
        services: PlatformServices,
    ): TextTranslator = TextTranslator(
        pipeline = TranslationPipeline(
            registry = registry,
            http = http,
            settings = { settings.current.value.translators.toSettings() },
            store = PreferencesKeyValueStore(services),
            uiLanguage = { "ru" },
        ),
        registry = registry,
        settings = settings,
        http = http,
    )

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides services: PlatformServices): AppGraph
    }
}

fun createAppGraph(services: PlatformServices): AppGraph = createGraphFactory<AppGraph.Factory>().create(services)

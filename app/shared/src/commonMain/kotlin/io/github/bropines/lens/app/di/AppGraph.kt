package io.github.bropines.lens.app.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.github.bropines.lens.app.data.GalleryRepository
import io.github.bropines.lens.app.data.ScanRepository
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.gtranslate.GoogleTranslator
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
    val translator: GoogleTranslator
    val history: io.github.bropines.lens.app.data.history.HistoryRepository

    /** One HTTP client for the app; the engine (OkHttp) comes from the classpath. */
    @SingleIn(AppScope::class)
    @Provides
    fun httpClient(): HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
        }
    }

    @SingleIn(AppScope::class)
    @Provides
    fun lensClient(http: HttpClient): LensClient = LensClient(http)

    @SingleIn(AppScope::class)
    @Provides
    fun googleTranslator(http: HttpClient): GoogleTranslator = GoogleTranslator(http)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides services: PlatformServices): AppGraph
    }
}

fun createAppGraph(services: PlatformServices): AppGraph = createGraphFactory<AppGraph.Factory>().create(services)

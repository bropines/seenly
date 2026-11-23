package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.translate.KeyValueStore
import io.github.bropines.lens.translate.PipelineResult
import io.github.bropines.lens.translate.TranslationPipeline
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslatorRegistry
import io.github.bropines.lens.translate.missingOption
import io.github.bropines.lens.translate.SimpleProviderHost
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * What the screens use to translate text: the user's engines through the
 * fail-over pipeline, plus their names for the UI.
 */
class TextTranslator(
    private val pipeline: TranslationPipeline,
    val registry: TranslatorRegistry,
    private val settings: SettingsRepository,
    private val http: HttpClient,
) {
    /** [engine] null means the user's first enabled engine, falling over to the next. */
    suspend fun translate(text: String, target: String, source: String? = null, engine: String? = null, dictionary: Boolean = false): PipelineResult =
        pipeline.translate(text, target, source, engine, dictionary)

    fun nameOf(id: String): String = registry[id]?.name ?: id

    /** Enabled engines in the user's order. */
    fun enabled(): List<TranslationProvider> = settings.current.value.translators.enabled.mapNotNull { registry[it] }

    /** True when [provider] still lacks a required key or URL. */
    fun needsSetup(provider: TranslationProvider): Boolean {
        val options = settings.current.value.translators.options[provider.id].orEmpty()
        return provider.missingOption(SimpleProviderHost(http, options)) != null
    }
}

/** Providers' private state (DeepL's instance id, Bing's session, Google's bench) kept across restarts. */
class PreferencesKeyValueStore(services: PlatformServices) : KeyValueStore {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { "${services.dataDir}/translator-store.preferences_pb".toPath() },
    )

    override suspend fun get(key: String): String? = store.data.map { it[stringPreferencesKey(key)] }.first()

    override suspend fun set(key: String, value: String?) {
        store.edit { if (value == null) it.remove(stringPreferencesKey(key)) else it[stringPreferencesKey(key)] = value }
    }
}

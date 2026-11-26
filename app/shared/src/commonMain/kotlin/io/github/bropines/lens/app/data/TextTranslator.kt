package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.translate.KeyValueStore
import io.github.bropines.lens.translate.PipelineResult
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.TranslationException
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
    /**
     * [engine] null means the user's first enabled engine, falling over to the
     * next. With the source on auto, text found to be in [target] already goes
     * into the second language instead (see swapTarget).
     */
    suspend fun translate(text: String, target: String, source: String? = null, engine: String? = null, dictionary: Boolean = false): PipelineResult =
        translateTo(text, target, source, engine, dictionary).result

    /** A translation and the language it is really in: [target], or the second language when that took over. */
    data class Translated(val result: PipelineResult, val target: String)

    /** [translate], saying which language the text went into. */
    suspend fun translateTo(text: String, target: String, source: String? = null, engine: String? = null, dictionary: Boolean = false): Translated {
        val prefs = settings.settings.first().copy(targetLanguage = target, sourceLanguage = source)
        val first = try {
            pipeline.translate(text, target, source, engine, dictionary)
        } catch (e: TranslationException) {
            // An engine that found the text in the target language says so instead of translating:
            // that is exactly when the second language takes over.
            val other = prefs.swapTarget(target).takeIf { e.code == ErrorCode.SameLanguage } ?: throw e
            return Translated(pipeline.translate(text, other, source, engine, dictionary), other)
        }
        val other = prefs.swapTarget(first.translation.detectedSource) ?: return Translated(first, target)
        return try {
            Translated(pipeline.translate(text, other, source, engine, dictionary), other)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Translated(first, target)
        }
    }

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

package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.platform.PlatformServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

enum class ThemeMode { System, Light, Dark }

/** Which engine translates typed or copied text (images always go through Lens). */
enum class TextEngine { Google, Lens }

data class AppSettings(
    val targetLanguage: String = "ru",
    /** null = let Lens detect. */
    val sourceLanguage: String? = null,
    /** Lens OCR hint; empty = not stated, which is what Chromium sends. */
    val ocrLanguage: String = "",
    val readingLayout: ReadingLayout = ReadingLayout.InPlace,
    val textEngine: TextEngine = TextEngine.Google,
    val theme: ThemeMode = ThemeMode.System,
    /** The last folder opened, as FileKit's serialised PlatformFile, so it can be reopened. */
    val lastFolder: String? = null,
)

/** How translated text is laid over the image. */
enum class ReadingLayout {
    /** Line by line in the source's place, like Chromium. Right for signs and screenshots. */
    InPlace,

    /** Each paragraph reflowed into its box. Right for manga bubbles, where translations run longer. */
    Reflow,
}

@SingleIn(AppScope::class)
@Inject
class SettingsRepository(services: PlatformServices) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { "${services.dataDir}/settings.preferences_pb".toPath() },
    )

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val next = transform(p.toSettings())
            p[TARGET] = next.targetLanguage
            p[SOURCE] = next.sourceLanguage.orEmpty()
            p[OCR] = next.ocrLanguage
            p[LAYOUT] = next.readingLayout.name
            p[ENGINE] = next.textEngine.name
            p[THEME] = next.theme.name
            if (next.lastFolder != null) p[LAST_FOLDER] = next.lastFolder else p.remove(LAST_FOLDER)
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        targetLanguage = this[TARGET] ?: "ru",
        sourceLanguage = this[SOURCE]?.ifEmpty { null },
        ocrLanguage = this[OCR] ?: "",
        readingLayout = this[LAYOUT].toEnum(ReadingLayout.InPlace),
        textEngine = this[ENGINE].toEnum(TextEngine.Google),
        theme = this[THEME].toEnum(ThemeMode.System),
        lastFolder = this[LAST_FOLDER],
    )

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private companion object {
        val TARGET = stringPreferencesKey("target_language")
        val SOURCE = stringPreferencesKey("source_language")
        val OCR = stringPreferencesKey("ocr_language")
        val LAYOUT = stringPreferencesKey("reading_layout")
        val ENGINE = stringPreferencesKey("text_engine")
        val THEME = stringPreferencesKey("theme")
        val LAST_FOLDER = stringPreferencesKey("last_folder")
    }
}

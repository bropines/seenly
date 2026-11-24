package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.translate.TranslatorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

enum class ThemeMode { System, Light, Dark }

/** Which engine translates typed or copied text (images always go through Lens). */
enum class TextEngine { Google, Lens }

@Serializable
data class AppSettings(
    val targetLanguage: String = "ru",
    /** null = let Lens detect. */
    val sourceLanguage: String? = null,
    /** Lens OCR hint; empty = not stated, which is what Chromium sends. */
    val ocrLanguage: String = "",
    val readingLayout: ReadingLayout = ReadingLayout.Auto,
    val textEngine: TextEngine = TextEngine.Google,
    val theme: ThemeMode = ThemeMode.System,
    /** The last folder opened, as FileKit's serialised PlatformFile, so it can be reopened. */
    val lastFolder: String? = null,
    val overlay: OverlayPrefs = OverlayPrefs(),
    val translators: TranslatorPrefs = TranslatorPrefs(),
)

/**
 * The text translators: which engines are on, in what order, and their keys
 * and options. Images always go through Lens; this is for typed, copied and
 * selected text.
 */
@Serializable
data class TranslatorPrefs(
    val enabled: List<String> = listOf("google", "bing", "yandex"),
    val failOver: Boolean = true,
    val options: Map<String, Map<String, String>> = emptyMap(),
) {
    fun toSettings() = TranslatorSettings(enabled = enabled, options = options, failOver = failOver)
}

/**
 * How the translation is drawn. Each `Auto` keeps what the chosen layout's
 * preset does (Chromium for signs, the manga preset for bubbles).
 */
@Serializable
data class OverlayPrefs(
    val erase: EraseChoice = EraseChoice.Auto,
    val outline: OutlineChoice = OutlineChoice.Auto,
    /** Multiplier on the fitted text size, 0.7..1.3. */
    val textScale: Float = 1f,
    /** Smallest text in sp; 0 keeps the preset's floor. */
    val minFontSp: Int = 0,
    val font: FontChoice = FontChoice.System,
    val vertical: VerticalChoice = VerticalChoice.Auto,
)

enum class EraseChoice { Auto, Patch, Fill }
enum class OutlineChoice { Auto, None, Thin, Bold }
enum class FontChoice { System, Sans, Serif, Mono }
enum class VerticalChoice { Auto, Keep, Horizontal }

/** How translated text is laid over the image. */
enum class ReadingLayout {
    /** Reflow when the page has vertical (manga-style) text, in place otherwise. */
    Auto,

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

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The latest settings, for code that cannot suspend (the translation pipeline reads its engines here). */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

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
            p[ERASE] = next.overlay.erase.name
            p[OUTLINE] = next.overlay.outline.name
            p[TEXT_SCALE] = next.overlay.textScale
            p[MIN_FONT] = next.overlay.minFontSp
            p[FONT] = next.overlay.font.name
            p[VERTICAL] = next.overlay.vertical.name
            p[TRANSLATORS] = json.encodeToString(TranslatorPrefs.serializer(), next.translators)
        }
    }

    /**
     * Every setting as JSON, translator keys included (in plain text: the
     * file is the user's to guard). The last folder is left out: it is a
     * permission on this device and means nothing on another.
     */
    suspend fun exportJson(): String =
        backupJson.encodeToString(Backup.serializer(), Backup(settings = settings.first().copy(lastFolder = null)))

    /** Replaces the settings with a file [exportJson] wrote. Throws [IllegalArgumentException] on anything else. */
    suspend fun importJson(text: String) {
        val backup = try {
            backupJson.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a settings file", e)
        }
        require(backup.format == BACKUP_FORMAT) { "not a settings file" }
        update { backup.settings.copy(lastFolder = it.lastFolder) }
    }

    @Serializable
    private data class Backup(val format: String = BACKUP_FORMAT, val version: Int = 1, val settings: AppSettings)

    private val backupJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun Preferences.toSettings() = AppSettings(
        targetLanguage = this[TARGET] ?: "ru",
        sourceLanguage = this[SOURCE]?.ifEmpty { null },
        ocrLanguage = this[OCR] ?: "",
        readingLayout = this[LAYOUT].toEnum(ReadingLayout.Auto),
        textEngine = this[ENGINE].toEnum(TextEngine.Google),
        theme = this[THEME].toEnum(ThemeMode.System),
        lastFolder = this[LAST_FOLDER],
        overlay = OverlayPrefs(
            erase = this[ERASE].toEnum(EraseChoice.Auto),
            outline = this[OUTLINE].toEnum(OutlineChoice.Auto),
            textScale = (this[TEXT_SCALE] ?: 1f).coerceIn(0.7f, 1.3f),
            minFontSp = (this[MIN_FONT] ?: 0).coerceIn(0, 16),
            font = this[FONT].toEnum(FontChoice.System),
            vertical = this[VERTICAL].toEnum(VerticalChoice.Auto),
        ),
        translators = this[TRANSLATORS]
            ?.let { runCatching { json.decodeFromString(TranslatorPrefs.serializer(), it) }.getOrNull() }
            ?: TranslatorPrefs(),
    )

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private companion object {
        const val BACKUP_FORMAT = "lens-kmp-settings"
        val TARGET = stringPreferencesKey("target_language")
        val SOURCE = stringPreferencesKey("source_language")
        val OCR = stringPreferencesKey("ocr_language")
        val LAYOUT = stringPreferencesKey("reading_layout")
        val ENGINE = stringPreferencesKey("text_engine")
        val THEME = stringPreferencesKey("theme")
        val LAST_FOLDER = stringPreferencesKey("last_folder")
        val ERASE = stringPreferencesKey("overlay_erase")
        val OUTLINE = stringPreferencesKey("overlay_outline")
        val TEXT_SCALE = floatPreferencesKey("overlay_text_scale")
        val MIN_FONT = intPreferencesKey("overlay_min_font")
        val FONT = stringPreferencesKey("overlay_font")
        val TRANSLATORS = stringPreferencesKey("translators")
        val VERTICAL = stringPreferencesKey("overlay_vertical")
    }
}

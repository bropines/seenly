package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/** The palette's seed: the wallpaper's colours (Android 12+), or one of Seenly's presets. */
enum class ThemeColor { Wallpaper, Violet, Blue, Teal, Green, Amber, Red, Pink, Mono }

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
    val themeColor: ThemeColor = ThemeColor.Wallpaper,
    /** Pure black backgrounds in the dark theme, for OLED screens. */
    val amoled: Boolean = false,
    /** The viewer's controls folded to one row (languages, rotate, the eye); remembered between pictures. */
    val viewerCompact: Boolean = false,
    /** What the viewer shows over a new camera shot: the mode last chosen for one. */
    val cameraMode: ViewMode = ViewMode.Translate,
    /**
     * With the source on auto, text found to be in [targetLanguage] already is
     * translated into this language instead; null turns that off.
     */
    val secondLanguage: String? = DEFAULT_SECOND_LANGUAGE,
    /** Seenly's own entries in the system share sheet, each opening the picture in that mode. */
    val shareTargets: Set<ViewMode> = ViewMode.entries.toSet(),
    val screen: ScreenSettings = ScreenSettings(),
    /** Where search results and other links open. */
    val linkOpening: LinkOpening = LinkOpening.Sheet,
    /** The UI language as a BCP 47 tag ("en", "ru"); null follows the system. */
    val uiLanguage: String? = null,
    /** The last folder opened, as FileKit's serialised PlatformFile, so it can be reopened. */
    val lastFolder: String? = null,
    val overlay: OverlayPrefs = OverlayPrefs(),
    val translators: TranslatorPrefs = TranslatorPrefs(),
    val updates: UpdatePrefs = UpdatePrefs(),
    val onDevice: OnDevicePrefs = OnDevicePrefs(),
    val account: AccountPrefs = AccountPrefs(),
    val proxy: ProxyPrefs = ProxyPrefs(),
)

const val DEFAULT_SECOND_LANGUAGE = "en"

/** Where links open: a sheet over Seenly (half the screen, can be pulled up), a full tab, or the browser app. */
enum class LinkOpening { Sheet, Tab, Browser }

/**
 * What the viewer opens a picture with: the translation, its text to select,
 * visual search, or visual search with the question for the AI already asked for.
 */
enum class ViewMode { Translate, Text, Search, AskAi }

/** The floating screen translator's preferences that are not about where its windows sit. */
@Serializable
data class ScreenSettings(
    /** Translate as soon as it is turned on, a mode is picked or the menu's main button is pressed; else wait for a tap. */
    val translateOnStart: Boolean = true,
    /** How dark the screen goes behind the bubble's menu, in percent. */
    val menuDim: Int = 40,
    /** How dark the screen goes behind a "this screen" translation, in percent. */
    val resultDim: Int = 0,
)

/**
 * The language to translate into instead of [AppSettings.targetLanguage] when
 * Lens or a text engine found the text to be in that language already
 * ([detected]), or null to keep it.
 */
fun AppSettings.swapTarget(detected: String?): String? {
    val second = secondLanguage ?: return null
    if (sourceLanguage != null || detected == null) return null
    if (!sameLanguage(detected, targetLanguage) || sameLanguage(second, targetLanguage)) return null
    return second
}

/** "en" and "en-US" are one language; "zh-CN" and "zh-TW" are two. */
internal fun sameLanguage(a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = true)) return true
    val (baseA, regionA) = a.lowercase().replace('_', '-').let { it.substringBefore('-') to it.substringAfter('-', "") }
    val (baseB, regionB) = b.lowercase().replace('_', '-').let { it.substringBefore('-') to it.substringAfter('-', "") }
    return baseA == baseB && (regionA.isEmpty() || regionB.isEmpty() || regionA == regionB)
}

/** Where the app's own requests go out through. */
enum class ProxyMode {
    /** Straight to the network, whatever the system's proxy says. */
    None,

    /** The proxy the system has for the current network (Android's Wi-Fi proxy, Java's proxy properties). */
    System,
    Http,
    Socks5,
}

/**
 * The optional proxy for the app's requests. The password is a secret and is
 * not kept here: see `AppNetwork`.
 */
@Serializable
data class ProxyPrefs(
    // System by default: what OkHttp did before there was a setting, so a Wi-Fi proxy keeps working.
    val mode: ProxyMode = ProxyMode.System,
    val host: String = "",
    /** 0 = not set. */
    val port: Int = 0,
    /** Empty = the proxy asks for no sign-in. */
    val username: String = "",
)

/** The in-app updater's preferences. When it last asked is state, not a preference, and lives elsewhere. */
@Serializable
data class UpdatePrefs(
    /** Ask GitHub for a newer release when the app starts, at most twice a day. */
    val autoCheck: Boolean = true,
    /** Also offer betas of the next version. */
    val beta: Boolean = false,
)

/** On-device recognition and translation (the `google` flavor); ignored where the build has none. */
@Serializable
data class OnDevicePrefs(
    /** Scan on the device when Lens cannot be reached or refuses. */
    val fallback: Boolean = true,
    /** Never send images to Lens: scan on the device only. */
    val always: Boolean = false,
    /** Download language models over Wi-Fi only. */
    val wifiOnly: Boolean = true,
)

/** The optional Google account. The cookies themselves are a secret and are not kept here. */
@Serializable
data class AccountPrefs(
    /** Send Lens requests signed in when an account is connected. */
    val useForLens: Boolean = true,
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
    /** Line by line: one size for all lines of a paragraph instead of each its own. */
    val uniformLineSize: RefineChoice = RefineChoice.Auto,
    /** Line by line: a line widened to the readable size grows from its start, keeping indents. */
    val growFromLineStart: RefineChoice = RefineChoice.Auto,
    /** Line by line: paragraphs of several lines are re-wrapped as one block, as in manga. */
    val reflowDense: RefineChoice = RefineChoice.Auto,
)

/** A layout refinement: decided per paragraph from its shape, always, or never. */
enum class RefineChoice { Auto, On, Off }

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
            p[THEME_COLOR] = next.themeColor.name
            p[AMOLED] = next.amoled
            p[VIEWER_COMPACT] = next.viewerCompact
            p[CAMERA_MODE] = next.cameraMode.name
            // Empty is "off", set on purpose; no key at all is the default.
            p[SECOND_LANGUAGE] = next.secondLanguage.orEmpty()
            p[SHARE_TARGETS] = ViewMode.entries.filter { it in next.shareTargets }.joinToString(",") { it.name }
            p[LINK_OPENING] = next.linkOpening.name
            p[SCREEN_AUTO_START] = next.screen.translateOnStart
            p[SCREEN_MENU_DIM] = next.screen.menuDim
            p[SCREEN_RESULT_DIM] = next.screen.resultDim
            if (next.uiLanguage != null) p[UI_LANGUAGE] = next.uiLanguage else p.remove(UI_LANGUAGE)
            if (next.lastFolder != null) p[LAST_FOLDER] = next.lastFolder else p.remove(LAST_FOLDER)
            p[ERASE] = next.overlay.erase.name
            p[OUTLINE] = next.overlay.outline.name
            p[TEXT_SCALE] = next.overlay.textScale
            p[MIN_FONT] = next.overlay.minFontSp
            p[FONT] = next.overlay.font.name
            p[VERTICAL] = next.overlay.vertical.name
            p[UNIFORM_SIZE] = next.overlay.uniformLineSize.name
            p[GROW_FROM_START] = next.overlay.growFromLineStart.name
            p[REFLOW_DENSE] = next.overlay.reflowDense.name
            p[TRANSLATORS] = json.encodeToString(TranslatorPrefs.serializer(), next.translators)
            p[UPDATE_AUTO] = next.updates.autoCheck
            p[UPDATE_BETA] = next.updates.beta
            p[LOCAL_FALLBACK] = next.onDevice.fallback
            p[LOCAL_ALWAYS] = next.onDevice.always
            p[LOCAL_WIFI] = next.onDevice.wifiOnly
            p[ACCOUNT_LENS] = next.account.useForLens
            p[PROXY_MODE] = next.proxy.mode.name
            p[PROXY_HOST] = next.proxy.host
            p[PROXY_PORT] = next.proxy.port
            p[PROXY_USER] = next.proxy.username
        }
    }

    /**
     * Every setting as JSON, translator keys included (in plain text: the
     * file is the user's to guard). The last folder is left out: it is a
     * permission on this device and means nothing on another.
     *
     * [googleCookies] is the Google account's session, and goes in only when
     * the user asked for it: whoever reads the file is signed in as them.
     * [proxyPassword] likewise: the proxy's address and user name are settings,
     * its password only goes in when asked.
     */
    suspend fun exportJson(googleCookies: String? = null, proxyPassword: String? = null): String = backupJson.encodeToString(
        Backup.serializer(),
        Backup(settings = settings.first().copy(lastFolder = null), googleCookies = googleCookies, proxyPassword = proxyPassword),
    )

    /** The secrets a settings file carried, for the caller to put where secrets are kept. */
    data class ImportedSecrets(val googleCookies: String?, val proxyPassword: String?)

    /**
     * Replaces the settings with a file [exportJson] wrote, and returns the
     * secrets it carried, if any, for the caller to store.
     * Throws [IllegalArgumentException] on anything else.
     */
    suspend fun importJson(text: String): ImportedSecrets {
        val backup = try {
            backupJson.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a settings file", e)
        }
        require(backup.format == BACKUP_FORMAT) { "not a settings file" }
        update { backup.settings.copy(lastFolder = it.lastFolder) }
        return ImportedSecrets(backup.googleCookies, backup.proxyPassword)
    }

    @Serializable
    private data class Backup(
        val format: String = BACKUP_FORMAT,
        val version: Int = 1,
        val settings: AppSettings,
        val googleCookies: String? = null,
        val proxyPassword: String? = null,
    )

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
        themeColor = this[THEME_COLOR].toEnum(ThemeColor.Wallpaper),
        amoled = this[AMOLED] ?: false,
        viewerCompact = this[VIEWER_COMPACT] ?: false,
        cameraMode = this[CAMERA_MODE].toEnum(ViewMode.Translate),
        secondLanguage = (this[SECOND_LANGUAGE] ?: DEFAULT_SECOND_LANGUAGE).ifEmpty { null },
        shareTargets = this[SHARE_TARGETS]?.split(',')?.mapNotNull { name -> ViewMode.entries.firstOrNull { it.name == name } }?.toSet()
            ?: ViewMode.entries.toSet(),
        linkOpening = this[LINK_OPENING].toEnum(LinkOpening.Sheet),
        screen = ScreenSettings(
            translateOnStart = this[SCREEN_AUTO_START] ?: true,
            menuDim = (this[SCREEN_MENU_DIM] ?: 40).coerceIn(0, 90),
            resultDim = (this[SCREEN_RESULT_DIM] ?: 0).coerceIn(0, 90),
        ),
        uiLanguage = this[UI_LANGUAGE]?.ifEmpty { null },
        lastFolder = this[LAST_FOLDER],
        overlay = OverlayPrefs(
            erase = this[ERASE].toEnum(EraseChoice.Auto),
            outline = this[OUTLINE].toEnum(OutlineChoice.Auto),
            textScale = (this[TEXT_SCALE] ?: 1f).coerceIn(0.7f, 1.3f),
            minFontSp = (this[MIN_FONT] ?: 0).coerceIn(0, 16),
            font = this[FONT].toEnum(FontChoice.System),
            vertical = this[VERTICAL].toEnum(VerticalChoice.Auto),
            uniformLineSize = this[UNIFORM_SIZE].toEnum(RefineChoice.Auto),
            growFromLineStart = this[GROW_FROM_START].toEnum(RefineChoice.Auto),
            reflowDense = this[REFLOW_DENSE].toEnum(RefineChoice.Auto),
        ),
        translators = this[TRANSLATORS]
            ?.let { runCatching { json.decodeFromString(TranslatorPrefs.serializer(), it) }.getOrNull() }
            ?: TranslatorPrefs(),
        updates = UpdatePrefs(autoCheck = this[UPDATE_AUTO] ?: true, beta = this[UPDATE_BETA] ?: false),
        onDevice = OnDevicePrefs(
            fallback = this[LOCAL_FALLBACK] ?: true,
            always = this[LOCAL_ALWAYS] ?: false,
            wifiOnly = this[LOCAL_WIFI] ?: true,
        ),
        account = AccountPrefs(useForLens = this[ACCOUNT_LENS] ?: true),
        proxy = ProxyPrefs(
            mode = this[PROXY_MODE].toEnum(ProxyMode.System),
            host = this[PROXY_HOST].orEmpty(),
            port = (this[PROXY_PORT] ?: 0).coerceIn(0, 65535),
            username = this[PROXY_USER].orEmpty(),
        ),
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
        val THEME_COLOR = stringPreferencesKey("theme_color")
        val AMOLED = booleanPreferencesKey("theme_amoled")
        val VIEWER_COMPACT = booleanPreferencesKey("viewer_compact")
        val CAMERA_MODE = stringPreferencesKey("camera_view_mode")
        val SECOND_LANGUAGE = stringPreferencesKey("second_language")
        val SHARE_TARGETS = stringPreferencesKey("share_targets")
        val SCREEN_AUTO_START = booleanPreferencesKey("screen_translate_on_start")
        val LINK_OPENING = stringPreferencesKey("link_opening")
        val SCREEN_MENU_DIM = intPreferencesKey("screen_menu_dim")
        val SCREEN_RESULT_DIM = intPreferencesKey("screen_result_dim")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val LAST_FOLDER = stringPreferencesKey("last_folder")
        val ERASE = stringPreferencesKey("overlay_erase")
        val OUTLINE = stringPreferencesKey("overlay_outline")
        val TEXT_SCALE = floatPreferencesKey("overlay_text_scale")
        val MIN_FONT = intPreferencesKey("overlay_min_font")
        val FONT = stringPreferencesKey("overlay_font")
        val TRANSLATORS = stringPreferencesKey("translators")
        val VERTICAL = stringPreferencesKey("overlay_vertical")
        val UNIFORM_SIZE = stringPreferencesKey("overlay_uniform_size")
        val GROW_FROM_START = stringPreferencesKey("overlay_grow_from_start")
        val REFLOW_DENSE = stringPreferencesKey("overlay_reflow_dense")
        val UPDATE_AUTO = booleanPreferencesKey("update_auto_check")
        val UPDATE_BETA = booleanPreferencesKey("update_beta")
        val LOCAL_FALLBACK = booleanPreferencesKey("on_device_fallback")
        val LOCAL_ALWAYS = booleanPreferencesKey("on_device_always")
        val LOCAL_WIFI = booleanPreferencesKey("on_device_wifi_only")
        val ACCOUNT_LENS = booleanPreferencesKey("account_use_for_lens")
        val PROXY_MODE = stringPreferencesKey("proxy_mode")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_username")
    }
}

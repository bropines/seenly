package io.github.bropines.lens.translate

import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One translation engine.
 *
 * A provider is a stateless description plus a few suspend functions.
 * Everything it needs at run time arrives in the [ProviderHost] passed to each
 * call: the HTTP client, the values of its [options], the UI language and a
 * small key-value store for anything it wants to remember (a session, a
 * device id, a rate-limit bench). Because of that a provider is tested against
 * recorded responses with a mock HTTP engine, and a provider written in
 * another language (see the module README, "Scripted providers") is just
 * another implementation of this interface.
 *
 * Language tags in [TranslationRequest] and [Translation] are the library's
 * internal BCP 47 tags (see [Languages]); a provider converts them to its own
 * codes through [languages].
 *
 * Failures are reported by throwing [TranslationException]; a response the
 * provider cannot read is [ErrorCode.BadResponse], never a crash.
 */
public interface TranslationProvider {
    /** Stable identifier, used in settings and in [TranslatorRegistry]. */
    public val id: String

    /** Name shown to the user. */
    public val name: String

    public val description: String get() = ""

    public val capabilities: Set<Capability>

    /** Which languages the engine knows and what it calls them. */
    public val languages: LanguageMap

    /** Longest text one [translate] call accepts; [TranslationPipeline] chunks by it. */
    public val maxInputChars: Int

    /** True when the engine cannot work until the user fills in a key. */
    public val needsCredentials: Boolean get() = false

    /** The engine's settings; a settings page is built from these. */
    public val options: List<OptionSpec> get() = emptyList()

    /** How long [TranslationPipeline] waits for one request before moving on. */
    public val requestTimeout: Duration get() = 15.seconds

    /**
     * Translates one piece of text of at most [maxInputChars] characters.
     * A null source in the request asks the engine to detect the language,
     * which needs [Capability.AutoDetect].
     */
    public suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation

    /** Detects the language of [text]. Needs [Capability.Detect]. */
    public suspend fun detect(text: String, host: ProviderHost): Detection? =
        throw TranslationException(ErrorCode.Unsupported, "$name cannot detect a language")

    /**
     * Dictionary entries for a word or short phrase. Needs [Capability.Dictionary].
     * The default asks [translate] for them.
     */
    public suspend fun dictionary(request: TranslationRequest, host: ProviderHost): List<DictionaryEntry> {
        if (Capability.Dictionary !in capabilities) throw TranslationException(ErrorCode.Unsupported, "$name has no dictionary")
        return translate(request.copy(dictionary = true), host).dictionary
    }
}

public enum class Capability {
    /** [TranslationProvider.translate] accepts a null source. */
    AutoDetect,

    /** [TranslationProvider.detect] works. */
    Detect,

    /** Fills [Translation.dictionary] when the request asks for it. */
    Dictionary,

    /** May fill [Translation.transliteration]. */
    Transliteration,

    /** May fill [Translation.alternatives]. */
    Alternatives,
}

public data class TranslationRequest(
    val text: String,
    /** Internal BCP 47 tag of the target language. */
    val target: String,
    /** Internal BCP 47 tag of the source language, or null to detect it. */
    val source: String? = null,
    /** Also ask for dictionary entries (single words and short phrases). */
    val dictionary: Boolean = false,
)

public data class Translation(
    val text: String,
    /** The source language the engine detected or was given, as an internal BCP 47 tag. */
    val detectedSource: String? = null,
    /** Detection confidence, 0..1, when the engine reports one. */
    val confidence: Float? = null,
    /** Romanisation of the translation. */
    val transliteration: String? = null,
    /** Romanisation of the source. For an English source Google puts phonetics here. */
    val sourceTransliteration: String? = null,
    /** Other whole-text translations. */
    val alternatives: List<String> = emptyList(),
    val dictionary: List<DictionaryEntry> = emptyList(),
)

/** One part of speech of a dictionary article, e.g. noun: cat, kitty. */
public data class DictionaryEntry(
    val partOfSpeech: String,
    val terms: List<DictionaryTerm>,
    /** The looked-up word as the dictionary spells it. */
    val headword: String? = null,
    /** Phonetic transcription of the headword. */
    val transcription: String? = null,
)

public data class DictionaryTerm(
    val text: String,
    /** Words in the source language that this term translates back to. */
    val meanings: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    /** Usage examples, source text first. */
    val examples: List<String> = emptyList(),
)

public data class Detection(
    /** Internal BCP 47 tag. */
    val language: String,
    val confidence: Float? = null,
)

/** The type of an [OptionSpec]; decides the control a settings page shows. */
public enum class OptionType { Text, Secret, Url, Select, Boolean, Number }

/**
 * One setting of a provider. Values are always strings ("true"/"false" for
 * [OptionType.Boolean], a decimal for [OptionType.Number]) so that they can be
 * stored anywhere and handed to a scripted provider unchanged.
 */
public data class OptionSpec(
    val key: String,
    val type: OptionType,
    val label: String,
    val default: String = "",
    val hint: String = "",
    /** A blank value is a [ErrorCode.MissingCredential] error. */
    val required: Boolean = false,
    /** For [OptionType.Select]: value to label. */
    val choices: List<Pair<String, String>> = emptyList(),
)

/** What a provider gets from the application for one call. */
public interface ProviderHost {
    public val http: HttpClient

    /** The user's values for the provider's [TranslationProvider.options], by key. Missing keys mean the default. */
    public val options: Map<String, String>

    /** BCP 47 tag of the UI language; engines name parts of speech in it. */
    public val uiLanguage: String

    /** Private to the provider. May be persistent (DeepL's instance id wants that) or in memory. */
    public val store: KeyValueStore

    /** Wall clock in epoch milliseconds; a test passes its own. */
    public fun nowMillis(): Long
}

/** A string-to-string store, the only state a provider keeps between calls. */
public interface KeyValueStore {
    public suspend fun get(key: String): String?

    /** A null [value] removes the key. */
    public suspend fun set(key: String, value: String?)
}

public class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** A plain [ProviderHost]; enough for tests and for calling one provider directly. */
public class SimpleProviderHost(
    override val http: HttpClient,
    override val options: Map<String, String> = emptyMap(),
    override val uiLanguage: String = "en",
    override val store: KeyValueStore = MemoryKeyValueStore(),
    private val clock: () -> Long = ::systemMillis,
) : ProviderHost {
    override fun nowMillis(): Long = clock()
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun systemMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** The value of option [key]: the user's, or the spec's default when that is blank. */
public fun TranslationProvider.optionValue(host: ProviderHost, key: String): String =
    host.options[key]?.trim()?.takeIf { it.isNotEmpty() } ?: options.firstOrNull { it.key == key }?.default.orEmpty()

/** The first required option the user left blank, or null. */
public fun TranslationProvider.missingOption(host: ProviderHost): OptionSpec? =
    options.firstOrNull { it.required && optionValue(host, it.key).isBlank() }

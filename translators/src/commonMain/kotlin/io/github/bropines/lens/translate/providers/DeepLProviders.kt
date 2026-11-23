package io.github.bropines.lens.translate.providers

import io.github.bropines.lens.translate.Capability
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.LanguageMap
import io.github.bropines.lens.translate.OptionSpec
import io.github.bropines.lens.translate.OptionType
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.Translation
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import io.github.bropines.lens.translate.internal.array
import io.github.bropines.lens.translate.internal.changed
import io.github.bropines.lens.translate.internal.exchange
import io.github.bropines.lens.translate.internal.get
import io.github.bropines.lens.translate.internal.int
import io.github.bropines.lens.translate.internal.jsonBody
import io.github.bropines.lens.translate.internal.rejectedKey
import io.github.bropines.lens.translate.internal.requireOptions
import io.github.bropines.lens.translate.internal.sourceCode
import io.github.bropines.lens.translate.internal.statusError
import io.github.bropines.lens.translate.internal.string
import io.github.bropines.lens.translate.internal.targetCode
import io.github.bropines.lens.translate.optionValue
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The languages DeepL translates; an approximation that the service itself corrects with an error. */
private val DEEPL_LANGUAGES = setOf(
    "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "he", "hu", "id", "it", "ja", "ko", "lt", "lv",
    "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant",
)

/** A source is a bare language; a target needs a variant for English, Portuguese and Chinese. */
private fun deepLLanguages(upperCase: Boolean, supported: Set<String>? = DEEPL_LANGUAGES) = LanguageMap(
    supported = supported,
    sourceCodes = mapOf("zh-Hans" to "zh", "zh-Hant" to "zh", "en-US" to "en", "en-GB" to "en", "pt-BR" to "pt", "pt-PT" to "pt"),
    targetCodes = mapOf(
        "en" to "en-US", "en-US" to "en-US", "en-GB" to "en-GB",
        "pt" to "pt-BR", "pt-BR" to "pt-BR", "pt-PT" to "pt-PT",
        "zh-Hans" to "zh-Hans", "zh-Hant" to "zh-Hant",
    ),
    upperCase = upperCase,
)

/**
 * DeepL without a key: the free endpoint of the iOS app. The request carries
 * an `instance_id`, a UUID made once and kept in the host's store, so give
 * this provider a persistent store.
 *
 * When the server starts answering 403, set the app version options to the
 * current App Store release; that is why they are options and not constants.
 */
public object DeepLWebProvider : TranslationProvider {
    override val id: String = "deepl-web"
    override val name: String = "DeepL"
    override val description: String = "Keyless endpoint of the DeepL iOS app. 1500 characters per request."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect)
    override val languages: LanguageMap = deepLLanguages(upperCase = false)
    override val maxInputChars: Int = 1500
    override val options: List<OptionSpec> = listOf(
        OptionSpec("app_version", OptionType.Text, "App version", default = "25.1", hint = "Version of the DeepL iOS app to present. Update it when DeepL answers 403."),
        OptionSpec("app_build", OptionType.Text, "App build", default = "1864046"),
        OptionSpec("os_version", OptionType.Text, "iOS version", default = "18.2.0"),
    )

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun instanceId(host: ProviderHost): String =
        host.store.get(INSTANCE_ID) ?: Uuid.random().toString().uppercase().also { host.store.set(INSTANCE_ID, it) }

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
        val version = optionValue(host, "app_version")
        val build = optionValue(host, "app_build")
        val os = optionValue(host, "os_version")
        val instance = instanceId(host)
        val reply = host.exchange {
            post("https://oneshot-free.www.deepl.com/v1/translate") {
                header("User-Agent", "DeepL/$version (com.linguee.DeepLMobileTranslator; build:$build; iOS $os)")
                header("Accept", "application/json")
                jsonBody(
                    buildJsonObject {
                        putJsonArray("text") { add(request.text) }
                        put("target_lang", to)
                        if (from != null) put("source_lang", from)
                        put("usage_type", "translate")
                        putJsonObject("app_information") {
                            put("os", "iOS")
                            put("os_version", os)
                            put("app_version", version)
                            put("app_build", build)
                            put("instance_id", instance)
                        }
                    },
                )
            }
        }
        if (!reply.ok) {
            throw when (reply.status) {
                403 -> TranslationException(ErrorCode.Http, "DeepL refused the request (403); the app version in this engine's settings may be out of date", httpStatus = 403)
                451 -> TranslationException(ErrorCode.Http, "DeepL does not serve this region (451)", httpStatus = 451)
                else -> reply.failure()
            }
        }
        val first = reply.json()["translations"][0]
        return Translation(
            text = first["text"].string() ?: throw changed("no translations[0].text"),
            detectedSource = (first["detected_source_language"].string() ?: from)?.let(languages::fromProvider),
        )
    }

    private const val INSTANCE_ID = "instance_id"
}

/** The official DeepL API. A key ending in `:fx` belongs to the free plan and goes to `api-free`. */
public object DeepLApiProvider : TranslationProvider {
    override val id: String = "deepl"
    override val name: String = "DeepL API"
    override val description: String = "Official API, free or Pro, with your own key."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect)
    override val languages: LanguageMap = deepLLanguages(upperCase = true)
    override val maxInputChars: Int = 10_000
    override val needsCredentials: Boolean = true
    override val options: List<OptionSpec> = listOf(
        OptionSpec("api_key", OptionType.Secret, "API key", required = true, hint = "A key ending in :fx is a free-plan key."),
        OptionSpec(
            "formality", OptionType.Select, "Formality", default = "default",
            hint = "Only applied to German, French, Italian, Spanish, Dutch, Polish, Portuguese, Japanese and Russian.",
            choices = listOf("default" to "Default", "prefer_more" to "More formal", "prefer_less" to "Less formal"),
        ),
    )

    internal fun endpoint(key: String): String =
        (if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com") + "/v2/translate"

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        requireOptions(host)
        val key = optionValue(host, "api_key")
        val to = targetCode(request)
        val from = sourceCode(request)
        val formality = optionValue(host, "formality").takeIf { it != "default" && it.isNotEmpty() && to in FORMALITY_TARGETS }
        val reply = host.exchange {
            post(endpoint(key)) {
                header("Authorization", "DeepL-Auth-Key $key")
                jsonBody(
                    buildJsonObject {
                        putJsonArray("text") { add(request.text) }
                        put("target_lang", to)
                        if (from != null) put("source_lang", from)
                        if (formality != null) put("formality", formality)
                    },
                )
            }
        }
        if (!reply.ok) {
            throw when (reply.status) {
                401, 403 -> rejectedKey(reply.status)
                // Waiting does not help, so no retryAfter; the status keeps the pipeline from retrying.
                456 -> TranslationException(ErrorCode.RateLimited, "the DeepL quota is used up", httpStatus = 456)
                else -> reply.failure(reply.jsonOrNull()["message"].string() ?: reply.body.take(200))
            }
        }
        val first = reply.json()["translations"][0]
        return Translation(
            text = first["text"].string() ?: throw changed("no translations[0].text"),
            detectedSource = (first["detected_source_language"].string() ?: from)?.let(languages::fromProvider),
        )
    }

    private val FORMALITY_TARGETS = setOf("DE", "FR", "IT", "ES", "NL", "PL", "PT-BR", "PT-PT", "JA", "RU")
}

/** DeepLX, a self-hosted server that speaks to DeepL. It answers HTTP 200 with its own `code`; both are checked. */
public object DeepLXProvider : TranslationProvider {
    override val id: String = "deeplx"
    override val name: String = "DeepLX"
    override val description: String = "Your own DeepLX server."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Alternatives)

    /** Open set: the server decides. DeepLX takes bare codes in upper case. */
    override val languages: LanguageMap = LanguageMap(codes = mapOf("zh-Hans" to "zh", "zh-Hant" to "zh-TW"), upperCase = true)
    override val maxInputChars: Int = 5000
    override val options: List<OptionSpec> = listOf(
        OptionSpec("url", OptionType.Url, "Server URL", default = "http://127.0.0.1:1188/translate", required = true),
        OptionSpec("token", OptionType.Secret, "Access token", hint = "Only if the server was started with one."),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        requireOptions(host)
        val to = targetCode(request)
        val from = sourceCode(request)
        val token = optionValue(host, "token")
        val reply = host.exchange {
            post(optionValue(host, "url")) {
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                jsonBody(
                    buildJsonObject {
                        put("text", request.text)
                        put("target_lang", to)
                        if (from != null) put("source_lang", from)
                    },
                )
            }
        }
        val json = reply.jsonOrNull()
        val code = json["code"].int() ?: reply.status
        if (!reply.ok || code != 200) {
            val status = if (reply.ok) code else reply.status
            if (status == 401) throw rejectedKey(status)
            throw statusError(status, json["message"].string() ?: reply.body.take(200), reply.retryAfter)
        }
        return Translation(
            text = json["data"].string() ?: throw changed("no data"),
            detectedSource = (json["source_lang"].string()?.takeIf { it.isNotEmpty() && !it.equals("auto", true) } ?: from)
                ?.let(languages::fromProvider),
            alternatives = json["alternatives"].array().mapNotNull { it.string() },
        )
    }
}

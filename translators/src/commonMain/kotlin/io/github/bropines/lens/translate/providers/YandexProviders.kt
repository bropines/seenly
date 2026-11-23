package io.github.bropines.lens.translate.providers

import io.github.bropines.lens.translate.Capability
import io.github.bropines.lens.translate.Detection
import io.github.bropines.lens.translate.DictionaryEntry
import io.github.bropines.lens.translate.DictionaryTerm
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.LanguageMap
import io.github.bropines.lens.translate.OptionSpec
import io.github.bropines.lens.translate.OptionType
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.Translation
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import io.github.bropines.lens.translate.internal.Reply
import io.github.bropines.lens.translate.internal.array
import io.github.bropines.lens.translate.internal.changed
import io.github.bropines.lens.translate.internal.exchange
import io.github.bropines.lens.translate.internal.formBody
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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Yandex Translate without a key. Every request names the client
 * `srv=browser_video_translation`; without it the server answers 410.
 * Errors also arrive with HTTP 200, in the body's `code`.
 */
public object YandexWebProvider : TranslationProvider {
    override val id: String = "yandex"
    override val name: String = "Yandex Translate"
    override val description: String = "Keyless web endpoint, with a dictionary for single words."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Detect, Capability.Dictionary)

    /** `zh` for both Chinese scripts, `tl`, `no`. */
    override val languages: LanguageMap = LanguageMap(
        codes = mapOf("zh-Hans" to "zh", "zh-Hant" to "zh", "fil" to "tl", "nb" to "no"),
    )
    override val maxInputChars: Int = 4000

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
        val reply = host.exchange {
            post("$API/translate") {
                parameter("srv", SRV)
                parameter("lang", if (from != null) "$from-$to" else to)
                header("Referer", REFERER)
                formBody("text" to request.text)
            }
        }
        val json = checked(reply)
        val text = json["text"].array().mapNotNull { it.string() }.ifEmpty { throw changed("no text") }.joinToString("\n")
        // "en-ru": the pair that was actually used.
        val pair = json["lang"].string()
        val detected = pair?.substringBefore('-')?.takeIf { it.isNotEmpty() && pair.contains('-') } ?: from
        val entries = if (request.dictionary && detected != null && isDictionaryQuery(request.text)) {
            // The translation is the product; a missing dictionary article is not a failure.
            try {
                lookup(host, request.text.trim(), "$detected-$to")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return Translation(text, detectedSource = detected?.let(languages::fromProvider), dictionary = entries)
    }

    override suspend fun detect(text: String, host: ProviderHost): Detection? {
        val reply = host.exchange {
            get("$API/detect") {
                parameter("srv", SRV)
                parameter("text", text.take(256))
                header("Referer", REFERER)
            }
        }
        val language = checked(reply)["lang"].string()?.takeIf { it.isNotEmpty() } ?: return null
        return Detection(languages.fromProvider(language))
    }

    /** The dictionary answers for 1-3 words of at most 60 characters. */
    internal fun isDictionaryQuery(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.length <= 60 && trimmed.split(WHITESPACE).size <= 3
    }

    private suspend fun lookup(host: ProviderHost, text: String, pair: String): List<DictionaryEntry> {
        val reply = host.exchange {
            get(DICTIONARY) {
                parameter("srv", SRV)
                parameter("ui", host.uiLanguage.substringBefore('-'))
                parameter("lang", pair)
                parameter("text", text)
                header("Referer", REFERER)
            }
        }
        if (!reply.ok) throw reply.failure()
        return reply.json()["def"].array().mapNotNull { def ->
            val terms = def["tr"].array().mapNotNull { tr ->
                DictionaryTerm(
                    text = tr["text"].string() ?: return@mapNotNull null,
                    meanings = tr["mean"].texts(),
                    synonyms = tr["syn"].texts(),
                    examples = tr["ex"].array().mapNotNull { ex ->
                        val source = ex["text"].string() ?: return@mapNotNull null
                        ex["tr"][0]["text"].string()?.let { "$source — $it" } ?: source
                    },
                )
            }
            if (terms.isEmpty()) return@mapNotNull null
            DictionaryEntry(def["pos"].string().orEmpty(), terms, headword = def["text"].string(), transcription = def["ts"].string())
        }
    }

    private fun JsonElement?.texts(): List<String> = array().mapNotNull { it["text"].string() }

    private fun checked(reply: Reply): JsonElement {
        val json = reply.jsonOrNull()
        val code = json["code"].int() ?: reply.status
        if (code == 200 && json != null) return json
        val message = json["message"].string() ?: reply.body.take(200)
        throw when (code) {
            // The pair is not supported; the pipeline can try another engine.
            501 -> TranslationException(ErrorCode.TargetLanguage, "Yandex does not translate this language pair: $message", httpStatus = code)
            200 -> changed("not JSON")
            else -> statusError(code, message, reply.retryAfter)
        }
    }

    private const val SRV = "browser_video_translation"
    private const val API = "https://translate.yandex.net/api/v1/tr.json"
    private const val DICTIONARY = "https://dictionary.yandex.net/dicservice.json/lookup"
    private const val REFERER = "https://translate.yandex.com/"
    private val WHITESPACE = Regex("\\s+")
}

/**
 * Yandex Cloud Translate. An API key goes as `Api-Key`; an IAM token (`t1.…`)
 * goes as `Bearer` and then the folder id is required.
 */
public object YandexCloudProvider : TranslationProvider {
    override val id: String = "yandex-cloud"
    override val name: String = "Yandex Cloud Translate"
    override val description: String = "Official API; needs a Yandex Cloud API key or IAM token."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Detect)
    override val languages: LanguageMap = LanguageMap(codes = mapOf("zh-Hans" to "zh", "zh-Hant" to "zh", "fil" to "tl", "nb" to "no"))
    override val maxInputChars: Int = 10_000
    override val needsCredentials: Boolean = true
    override val options: List<OptionSpec> = listOf(
        OptionSpec("api_key", OptionType.Secret, "API key or IAM token", required = true, hint = "An IAM token starts with t1. and also needs the folder id."),
        OptionSpec("folder_id", OptionType.Text, "Folder id", hint = "Required with an IAM token."),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
        val json = call(host, "translate") {
            putJsonArray("texts") { add(request.text) }
            put("targetLanguageCode", to)
            if (from != null) put("sourceLanguageCode", from)
        }
        val first = json["translations"][0]
        return Translation(
            text = first["text"].string() ?: throw changed("no translations[0].text"),
            detectedSource = (first["detectedLanguageCode"].string() ?: from)?.let(languages::fromProvider),
        )
    }

    override suspend fun detect(text: String, host: ProviderHost): Detection? {
        val json = call(host, "detect") { put("text", text.take(1000)) }
        return json["languageCode"].string()?.takeIf { it.isNotEmpty() }?.let { Detection(languages.fromProvider(it)) }
    }

    private suspend fun call(host: ProviderHost, method: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonElement {
        requireOptions(host)
        val key = optionValue(host, "api_key")
        val folder = optionValue(host, "folder_id")
        val iam = key.startsWith("t1.")
        if (iam && folder.isEmpty()) throw TranslationException(ErrorCode.MissingCredential, "$name: an IAM token needs the folder id")
        val reply = host.exchange {
            post("https://translate.api.cloud.yandex.net/translate/v2/$method") {
                header("Authorization", if (iam) "Bearer $key" else "Api-Key $key")
                jsonBody(
                    buildJsonObject {
                        body()
                        if (folder.isNotEmpty()) put("folderId", folder)
                    },
                )
            }
        }
        if (reply.status == 401 || reply.status == 403) throw rejectedKey(reply.status)
        if (!reply.ok) throw reply.failure(reply.jsonOrNull()["message"].string() ?: reply.body.take(200))
        return reply.json()
    }
}

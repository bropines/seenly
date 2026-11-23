package io.github.bropines.lens.translate.providers

import io.github.bropines.lens.translate.Capability
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
import io.github.bropines.lens.translate.internal.BROWSER_USER_AGENT
import io.github.bropines.lens.translate.internal.array
import io.github.bropines.lens.translate.internal.changed
import io.github.bropines.lens.translate.internal.exchange
import io.github.bropines.lens.translate.internal.formBody
import io.github.bropines.lens.translate.internal.get
import io.github.bropines.lens.translate.internal.int
import io.github.bropines.lens.translate.internal.jsonBody
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
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Lara Translate, through the endpoint of its web page. */
public object LaraProvider : TranslationProvider {
    override val id: String = "lara"
    override val name: String = "Lara"
    override val description: String = "Keyless web endpoint of laratranslate.com."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect)

    /** Open set of BCP 47 tags; Lara says which ones it does not know. */
    override val languages: LanguageMap = LanguageMap(codes = mapOf("zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW"))
    override val maxInputChars: Int = 5000

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
        val reply = host.exchange {
            post("https://webapi.laratranslate.com/translate") {
                header("Origin", SITE)
                header("Referer", "$SITE/")
                header("User-Agent", BROWSER_USER_AGENT)
                jsonBody(
                    buildJsonObject {
                        put("q", request.text)
                        put("source", from.orEmpty())
                        put("target", to)
                        putJsonArray("instructions") {}
                    },
                )
            }
        }
        val json = reply.jsonOrNull()
        val error = json["error"]
        if (error != null && error !is kotlinx.serialization.json.JsonNull) {
            val type = error["type"].string().orEmpty()
            val message = error["message"].string() ?: error.string() ?: type
            if (type == "UnsupportedLanguageError") {
                val isSource = from != null && message.contains(from, ignoreCase = true) && !message.contains(to, ignoreCase = true)
                throw TranslationException(if (isSource) ErrorCode.SourceLanguage else ErrorCode.TargetLanguage, "Lara: $message")
            }
            throw if (reply.ok) TranslationException(ErrorCode.BadResponse, "Lara: $message") else reply.failure(message)
        }
        if (!reply.ok) throw reply.failure()
        val content = (json ?: throw changed("not JSON"))["content"]
        return Translation(
            text = content["translation"].string() ?: throw changed("no content.translation"),
            detectedSource = (content["source_language"].string()?.takeIf { it.isNotEmpty() } ?: from)?.let(languages::fromProvider),
        )
    }

    private const val SITE = "https://laratranslate.com"
}

/** ModernMT, through the endpoint of its web page. */
public object ModernMtProvider : TranslationProvider {
    override val id: String = "modernmt"
    override val name: String = "ModernMT"
    override val description: String = "Keyless web endpoint of modernmt.com."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect)

    /** `zh-CN`, `zh-TW`, `tl`. */
    override val languages: LanguageMap = LanguageMap(codes = mapOf("zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW", "fil" to "tl"))
    override val maxInputChars: Int = 5000

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
        val reply = host.exchange {
            post("$SITE/translate") {
                // Appended as is: the site expects the slash percent-encoded.
                url.parameters.append("_data", "routes/translate")
                header("Origin", SITE)
                header("Referer", "$SITE/translate")
                header("User-Agent", BROWSER_USER_AGENT)
                formBody("text" to request.text, "targetLanguage" to to, "sourceLanguage" to from)
            }
        }
        if (!reply.ok) throw reply.failure()
        val json = reply.json()
        if (json["status"].string() != "success") {
            // Seen live: {"status":"error","error":{"":["Operation not allowed: …"]},"fields":[…]}.
            val fieldErrors = (json["error"] as? JsonObject)?.values?.flatMap { it.array() }?.mapNotNull { it.string() }
            val message = fieldErrors?.joinToString("; ")?.ifEmpty { null }
                ?: json["message"].string() ?: json["error"].string() ?: reply.body.take(200)
            throw TranslationException(ErrorCode.BadResponse, "ModernMT: $message")
        }
        val value = json["value"]
        return Translation(
            text = value["translation"].string() ?: throw changed("no value.translation"),
            detectedSource = (value["detectedLanguage"].string()?.takeIf { it.isNotEmpty() } ?: from)?.let(languages::fromProvider),
        )
    }

    private const val SITE = "https://www.modernmt.com"
}

/**
 * MyMemory. It cannot detect the source language, always answers HTTP 200
 * with the real status in `responseStatus`, and when the daily quota runs out
 * it puts a "MYMEMORY WARNING" sentence where the translation should be.
 * That sentence is never returned as a translation.
 *
 * The optional email raises the quota from 5k to 50k characters a day. It is
 * personal data and is sent only when the user typed it into this option.
 */
public object MyMemoryProvider : TranslationProvider {
    override val id: String = "mymemory"
    override val name: String = "MyMemory"
    override val description: String = "Keyless API, 500 characters per request, 5000 a day. Needs the source language."
    override val capabilities: Set<Capability> = emptySet()
    override val languages: LanguageMap = LanguageMap(codes = mapOf("zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW", "fil" to "tl", "nb" to "no"))
    override val maxInputChars: Int = 500
    override val options: List<OptionSpec> = listOf(
        OptionSpec(
            "email", OptionType.Text, "Email (optional)",
            hint = "Sent to MyMemory with every request; raises the daily quota from 5000 to 50000 characters.",
        ),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request)
            ?: throw TranslationException(ErrorCode.SourceLanguage, "MyMemory cannot detect the source language")
        val email = optionValue(host, "email")
        val reply = host.exchange {
            get("https://api.mymemory.translated.net/get") {
                parameter("q", request.text)
                parameter("langpair", "$from|$to")
                if (email.isNotEmpty()) parameter("de", email)
            }
        }
        val json = reply.jsonOrNull() ?: throw if (reply.ok) changed("not JSON") else reply.failure()
        val text = json["responseData"]["translatedText"].string()
        val details = json["responseDetails"].string().orEmpty()
        val quota = (json["quotaFinished"] as? JsonPrimitive)?.booleanOrNull == true ||
            text?.contains(QUOTA_WARNING, ignoreCase = true) == true || details.contains(QUOTA_WARNING, ignoreCase = true)
        if (quota) throw TranslationException(ErrorCode.RateLimited, "the MyMemory daily quota is used up", httpStatus = 429)
        val status = json["responseStatus"].int() ?: reply.status
        if (status != 200) {
            val message = details.ifEmpty { text.orEmpty() }
            if (message.contains("LANGUAGE", ignoreCase = true)) {
                throw TranslationException(ErrorCode.TargetLanguage, "MyMemory: $message", httpStatus = status)
            }
            throw statusError(status, message)
        }
        return Translation(text ?: throw changed("no responseData.translatedText"), detectedSource = request.source)
    }

    private const val QUOTA_WARNING = "MYMEMORY WARNING"
}

/**
 * Lingva, an open front end to Google Translate. Public instances lose their
 * access to Google now and then and start echoing the input, so output equal
 * to the input is an error when the input has three or more words and the
 * source is not the target.
 */
public object LingvaProvider : TranslationProvider {
    override val id: String = "lingva"
    override val name: String = "Lingva"
    override val description: String = "Google Translate through a Lingva instance."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Transliteration, Capability.Dictionary)

    /** Google's legacy codes, plus `zh` and `zh_HANT`. */
    override val languages: LanguageMap = LanguageMap(
        codes = mapOf("he" to "iw", "zh-Hans" to "zh", "zh-Hant" to "zh_HANT", "fil" to "tl", "nb" to "no", "jv" to "jw"),
        responseCodes = mapOf("zh-CN" to "zh-Hans", "zh-TW" to "zh-Hant"),
    )
    override val maxInputChars: Int = 5000
    override val options: List<OptionSpec> = listOf(
        OptionSpec("instance", OptionType.Url, "Instance", default = "https://lingva.ml", required = true),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        requireOptions(host)
        val to = targetCode(request)
        val from = sourceCode(request) ?: "auto"
        val instance = optionValue(host, "instance").trimEnd('/')
        val reply = host.exchange { get("$instance/api/v1/$from/$to/${request.text.encodeURLPathPart()}") }
        val json = reply.jsonOrNull()
        json["error"].string()?.let { throw if (reply.ok) TranslationException(ErrorCode.BadResponse, "Lingva: $it") else reply.failure(it) }
        if (!reply.ok) throw reply.failure()
        val text = (json ?: throw changed("not JSON"))["translation"].string() ?: throw changed("no translation")
        val info = json["info"]
        val detected = (info["detectedSource"].string()?.takeIf { it.isNotEmpty() } ?: request.source)?.let(languages::fromProvider)
        if (isEcho(request.text, text) && detected != request.target) {
            throw TranslationException(ErrorCode.BadResponse, "the Lingva instance returned the text untranslated; it has probably lost its access to Google")
        }
        return Translation(
            text = text,
            detectedSource = detected,
            transliteration = info["pronunciation"]["translation"].string()?.ifBlank { null },
            sourceTransliteration = info["pronunciation"]["query"].string()?.ifBlank { null },
            dictionary = info["extraTranslations"].array().mapNotNull { group ->
                val terms = group["list"].array().mapNotNull { item ->
                    DictionaryTerm(item["word"].string() ?: return@mapNotNull null, meanings = item["meanings"].array().mapNotNull { it.string() })
                }
                if (terms.isEmpty()) null else DictionaryEntry(group["type"].string().orEmpty(), terms)
            },
        )
    }

    internal fun isEcho(input: String, output: String): Boolean =
        input.trim() == output.trim() && input.trim().split(WHITESPACE).size >= 3

    private val WHITESPACE = Regex("\\s+")
}

/** LibreTranslate, usually a server of your own. */
public object LibreTranslateProvider : TranslationProvider {
    override val id: String = "libretranslate"
    override val name: String = "LibreTranslate"
    override val description: String = "A LibreTranslate server: your own, or a public one with a key."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Alternatives)

    /** `zh`, `zt` for Traditional, `tl`; `pb` (Portuguese) appears in responses. */
    override val languages: LanguageMap = LanguageMap(
        codes = mapOf("zh-Hans" to "zh", "zh-Hant" to "zt", "fil" to "tl"),
        responseCodes = mapOf("pb" to "pt-BR"),
    )
    override val maxInputChars: Int = 5000
    override val options: List<OptionSpec> = listOf(
        OptionSpec("url", OptionType.Url, "Server URL", default = "http://127.0.0.1:5000", required = true),
        OptionSpec("api_key", OptionType.Secret, "API key", hint = "Only if the server requires one."),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        requireOptions(host)
        val to = targetCode(request)
        val from = sourceCode(request)
        val key = optionValue(host, "api_key")
        val reply = host.exchange {
            post(optionValue(host, "url").trimEnd('/') + "/translate") {
                jsonBody(
                    buildJsonObject {
                        put("q", request.text)
                        put("source", from ?: "auto")
                        put("target", to)
                        put("format", "text")
                        if (key.isNotEmpty()) put("api_key", key)
                    },
                )
            }
        }
        val json = reply.jsonOrNull()
        if (!reply.ok) {
            val message = json["error"].string() ?: reply.body.take(200)
            if ((reply.status == 400 || reply.status == 403) && message.contains("api key", ignoreCase = true)) {
                throw TranslationException(ErrorCode.MissingCredential, "this LibreTranslate server requires an API key: $message", httpStatus = reply.status)
            }
            throw reply.failure(message)
        }
        return Translation(
            text = (json ?: throw changed("not JSON"))["translatedText"].string() ?: throw changed("no translatedText"),
            detectedSource = (json["detectedLanguage"]["language"].string() ?: from)?.let(languages::fromProvider),
            // LibreTranslate reports confidence as 0..100.
            confidence = (json["detectedLanguage"]["confidence"] as? JsonPrimitive)?.content?.toFloatOrNull()?.let { if (it > 1f) it / 100f else it },
            alternatives = json["alternatives"].array().mapNotNull { it.string() },
        )
    }
}

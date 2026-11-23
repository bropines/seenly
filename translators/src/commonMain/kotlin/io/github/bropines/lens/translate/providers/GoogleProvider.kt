package io.github.bropines.lens.translate.providers

import co.touchlab.kermit.Logger
import io.github.bropines.lens.translate.Capability
import io.github.bropines.lens.translate.Detection
import io.github.bropines.lens.translate.DictionaryEntry
import io.github.bropines.lens.translate.DictionaryTerm
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.LanguageMap
import io.github.bropines.lens.translate.Languages
import io.github.bropines.lens.translate.OptionSpec
import io.github.bropines.lens.translate.OptionType
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.Translation
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import io.github.bropines.lens.translate.internal.BROWSER_USER_AGENT
import io.github.bropines.lens.translate.internal.Reply
import io.github.bropines.lens.translate.internal.array
import io.github.bropines.lens.translate.internal.changed
import io.github.bropines.lens.translate.internal.exchange
import io.github.bropines.lens.translate.internal.float
import io.github.bropines.lens.translate.internal.formBody
import io.github.bropines.lens.translate.internal.get
import io.github.bropines.lens.translate.internal.sourceCode
import io.github.bropines.lens.translate.internal.string
import io.github.bropines.lens.translate.internal.targetCode
import io.github.bropines.lens.translate.optionValue
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Google Translate through the keyless `/translate_a/single` endpoint, the way
 * Glossa does it (docs/google-translate.md): three hosts in order, and a host
 * that answers with a captcha is benched with exponential backoff. The bench
 * lives in the host's store, so it survives for as long as the store does.
 *
 * A captcha is [ErrorCode.RateLimited] with the bench time as `retryAfter`;
 * that is longer than the pipeline's retry limit on purpose, because retrying
 * a refused host lengthens the block.
 */
public object GoogleProvider : TranslationProvider {
    private val log = Logger.withTag("GoogleProvider")

    override val id: String = "google"
    override val name: String = "Google Translate"
    override val description: String = "Keyless web endpoint. Dictionary and transliteration."
    override val capabilities: Set<Capability> =
        setOf(Capability.AutoDetect, Capability.Detect, Capability.Dictionary, Capability.Transliteration)

    /** Google's legacy codes. */
    override val languages: LanguageMap = LanguageMap(
        supported = Languages.all,
        codes = mapOf("he" to "iw", "zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW", "fil" to "tl", "nb" to "no", "jv" to "jw"),
    )

    /** One POST has carried far more, but Glossa stays at 10k per request. */
    override val maxInputChars: Int = 10_000

    override val options: List<OptionSpec> = listOf(
        OptionSpec(
            key = "tld",
            type = OptionType.Text,
            label = "Google domain",
            default = "com",
            hint = "The ending of translate.google.<domain>, e.g. com, co.jp, com.hk. It is tried last.",
        ),
    )

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val dt = if (request.dictionary) DICTIONARY_DT else BASIC_DT
        val parsed = parse(send(host, request.text, targetCode(request), sourceCode(request) ?: "auto", dt))
        return Translation(
            text = parsed.text,
            detectedSource = parsed.sourceLanguage?.takeIf { it != "und" }?.let(languages::fromProvider),
            confidence = parsed.confidence,
            transliteration = parsed.transliteration,
            sourceTransliteration = parsed.sourceTransliteration,
            dictionary = parsed.dictionary,
        )
    }

    /** There is no detect call: `sl=auto&tl=en&dt=ld`, reading the source slot. */
    override suspend fun detect(text: String, host: ProviderHost): Detection? {
        val parsed = parse(send(host, text.take(2000), "en", "auto", listOf("t", "ld")))
        val language = parsed.sourceLanguage?.takeIf { it != "und" } ?: return null
        return Detection(languages.fromProvider(language), parsed.confidence)
    }

    private class Endpoint(val url: String, val client: String) {
        val benchKey: String get() = "bench." + url.substringAfter("://").substringBefore('/')
    }

    private fun endpoints(host: ProviderHost): List<Endpoint> = listOf(
        Endpoint("https://clients5.google.com/translate_a/single", "dict-chrome-ex"),
        Endpoint("https://translate.googleapis.com/translate_a/single", "gtx"),
        // Last: a block here is shared with the user's own browser.
        Endpoint("https://translate.google.${safeTld(optionValue(host, "tld"))}/translate_a/single", "gtx"),
    )

    private suspend fun send(host: ProviderHost, text: String, target: String, source: String, dt: List<String>): String {
        var networkFailures = 0
        var lastError: TranslationException? = null
        var shortestBench: Duration? = null
        for (endpoint in endpoints(host)) {
            val bench = Bench.read(host, endpoint)
            val left = bench.leftAt(host.nowMillis())
            if (left > Duration.ZERO) {
                shortestBench = minOf(shortestBench ?: left, left)
                continue
            }
            val reply = try {
                request(host, endpoint, text, target, source, dt)
            } catch (e: TranslationException) {
                // A timeout is not the host's fault either, but waiting for two more would eat the budget.
                if (e.code != ErrorCode.Network) throw e
                // A second network failure in a row is the network, not the host.
                if (++networkFailures >= 2) throw e
                lastError = e
                continue
            }
            networkFailures = 0
            when (classify(reply.status, reply.headers["Content-Type"], reply.body)) {
                Verdict.Ok -> {
                    if (bench.strikes > 0) host.store.set(endpoint.benchKey, null)
                    return reply.body
                }
                Verdict.Captcha -> {
                    val wait = benchFor(bench.strikes + 1, reply.retryAfter)
                    host.store.set(endpoint.benchKey, "${bench.strikes + 1},${host.nowMillis() + wait.inWholeMilliseconds}")
                    log.w { "${endpoint.url} refused (captcha); benched for $wait" }
                    shortestBench = minOf(shortestBench ?: wait, wait)
                    lastError = null
                }
                Verdict.TryNext -> lastError = TranslationException(
                    ErrorCode.Http,
                    "${endpoint.url} answered ${reply.status}",
                    httpStatus = reply.status,
                )
                Verdict.Fail -> throw reply.failure("${endpoint.url}: ${reply.body.take(200)}")
            }
        }
        throw lastError ?: TranslationException(
            ErrorCode.RateLimited,
            "Google Translate refuses for now (captcha), retry in ${shortestBench ?: Duration.ZERO}",
            retryAfter = shortestBench,
        )
    }

    private suspend fun request(host: ProviderHost, endpoint: Endpoint, text: String, target: String, source: String, dt: List<String>): Reply {
        val url = URLBuilder().takeFrom(endpoint.url).apply {
            parameters.append("client", endpoint.client)
            parameters.append("sl", source)
            parameters.append("tl", target)
            parameters.append("hl", host.uiLanguage)
            dt.forEach { parameters.append("dt", it) }
            parameters.append("ie", "UTF-8")
            parameters.append("oe", "UTF-8")
        }
        val common: HttpRequestBuilder.() -> Unit = {
            header("User-Agent", BROWSER_USER_AGENT)
            header("Accept", "*/*")
            header("Accept-Language", host.uiLanguage)
        }
        // Beyond ~1800 url-encoded bytes the GET is refused (413/400); POST the text instead.
        return if (text.encodeURLParameter().length <= GET_LIMIT) {
            url.parameters.append("q", text)
            host.exchange { get(url.build()) { common() } }
        } else {
            host.exchange {
                post(url.build()) {
                    common()
                    formBody("q" to text)
                }
            }
        }
    }

    private class Bench(val strikes: Int, val untilMillis: Long) {
        fun leftAt(now: Long): Duration = (untilMillis - now).coerceAtLeast(0).milliseconds

        companion object {
            suspend fun read(host: ProviderHost, endpoint: Endpoint): Bench {
                val stored = host.store.get(endpoint.benchKey)?.split(',')
                return Bench(stored?.getOrNull(0)?.toIntOrNull() ?: 0, stored?.getOrNull(1)?.toLongOrNull() ?: 0)
            }
        }
    }

    internal enum class Verdict { Ok, Captcha, TryNext, Fail }

    internal const val GET_LIMIT: Int = 1800
    private val BASIC_DT = listOf("t", "rm", "ld")
    private val DICTIONARY_DT = listOf("t", "rm", "ld", "bd", "md", "ex", "ss")

    /** 30 s, doubling on each consecutive refusal up to 10 min, or Retry-After if that is longer. */
    internal fun benchFor(strikes: Int, retryAfter: Duration?): Duration {
        val backoff = (30.seconds * (1 shl (strikes - 1).coerceIn(0, 5))).coerceAtMost(10.minutes)
        return if (retryAfter != null && retryAfter > backoff) retryAfter else backoff
    }

    internal fun safeTld(tld: String): String =
        tld.takeIf { it.length in 2..16 && it.all { c -> c in 'a'..'z' || c == '.' } && !it.startsWith('.') } ?: "com"

    /**
     * Only an HTML page can be a captcha: a JSON reply echoes the source
     * text, which may itself contain "unusual traffic".
     */
    internal fun classify(status: Int, contentType: String?, body: String): Verdict {
        val html = contentType?.contains("text/html") == true || body.trimStart().startsWith("<")
        val captcha = html && body.take(64 * 1024).let { "/sorry/" in it || "unusual traffic" in it }
        return when {
            captcha || status == 429 -> Verdict.Captcha
            status in 200..299 && !html -> Verdict.Ok
            status == 403 || status >= 500 || html -> Verdict.TryNext
            else -> Verdict.Fail
        }
    }

    internal class Parsed(
        val text: String,
        val sourceLanguage: String?,
        val confidence: Float?,
        val sourceTransliteration: String?,
        val transliteration: String?,
        val dictionary: List<DictionaryEntry>,
    )

    /**
     * Reads the positional JSON array of `/translate_a/single`. Every access
     * is safe: a slot that moved or vanished gives less detail, never an
     * exception.
     */
    internal fun parse(body: String): Parsed {
        // The parser reads a bare word as a string, so "not an array" needs its own check.
        // Typed as JsonElement so that indexing goes through the safe get(), not List.get().
        val root: JsonElement = try {
            Json.parseToJsonElement(body) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: throw changed("unreadable reply: ${body.take(120)}")
        // The transliteration segment is the one whose [0] is not a string.
        val (textSegments, romanSegments) = root[0].array().partition { it[0].string() != null }
        val roman = romanSegments.lastOrNull()
        return Parsed(
            text = textSegments.joinToString("") { it[0].string().orEmpty() },
            sourceLanguage = root[2].string() ?: root[8][0][0].string(),
            confidence = root[6].float() ?: root[8][2][0].float(),
            transliteration = roman[2].string()?.ifBlank { null },
            sourceTransliteration = roman[3].string()?.ifBlank { null },
            dictionary = root[1].array().mapNotNull(::dictionaryEntry),
        )
    }

    private fun dictionaryEntry(entry: JsonElement): DictionaryEntry? {
        val pos = entry[0].string() ?: return null
        val back = entry[2].array().mapNotNull { item ->
            val word = item[0].string() ?: return@mapNotNull null
            word to item[1].array().mapNotNull { it.string() }
        }.toMap()
        val words = entry[1].array().mapNotNull { it.string() }.ifEmpty { back.keys.toList() }
        return DictionaryEntry(pos, words.map { DictionaryTerm(it, meanings = back[it].orEmpty()) }, headword = entry[3].string())
    }
}

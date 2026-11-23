package io.github.bropines.lens.gtranslate

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Google Translate through the keyless `/translate_a/single` endpoint, the way
 * Glossa does it (see docs/google-translate.md): three hosts in order, a host
 * that answers with a captcha is benched with exponential backoff, long text
 * goes in chunks.
 *
 * Google changes these endpoints without notice, so every failure mode ends in
 * a [GoogleTranslateException] rather than a crash.
 */
public class GoogleTranslator(
    private val http: HttpClient,
    tld: String = "com",
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val log = Logger.withTag("GoogleTranslator")
    private val hosts: List<Host> = listOf(
        Host("https://clients5.google.com/translate_a/single", "dict-chrome-ex"),
        Host("https://translate.googleapis.com/translate_a/single", "gtx"),
        // Last: a block here is shared with the user's own browser.
        Host("https://translate.google.${safeTld(tld)}/translate_a/single", "gtx"),
    )
    private val benchLock = Mutex()

    /**
     * Translates [text] into [target]. [source] is a language code or `auto`.
     * [uiLanguage] names the parts of speech in a dictionary entry.
     * [dictionary] asks for alternatives, definitions and examples, which
     * Google only returns for single words and short phrases.
     */
    public suspend fun translate(
        text: String,
        target: String,
        source: String = "auto",
        uiLanguage: String = "en",
        dictionary: Boolean = false,
    ): GoogleTranslation {
        if (text.isBlank()) return GoogleTranslation(text, source.takeIf { it != "auto" }, null, null, null, emptyList())
        val chunks = TextChunker.split(text)
        val dt = if (dictionary) DICTIONARY_DT else BASIC_DT
        val parts = chunks.mapIndexed { index, chunk ->
            // A dictionary only makes sense for the first (and usually only) chunk.
            val response = request(chunk.body, target, source, uiLanguage, if (index == 0) dt else BASIC_DT)
            chunk to TranslateResponseParser.parse(response)
        }
        val first = parts.first().second
        return GoogleTranslation(
            // Google trims each chunk's translation, so restore the source's whitespace between chunks.
            text = parts.joinToString("") { (chunk, parsed) -> chunk.leading + parsed.text.trim() + chunk.trailing },
            sourceLanguage = first.sourceLanguage,
            confidence = first.confidence,
            sourceTransliteration = parts.mapNotNull { it.second.sourceTransliteration }.joinToString(" ").ifEmpty { null },
            transliteration = parts.mapNotNull { it.second.transliteration }.joinToString(" ").ifEmpty { null },
            dictionary = first.dictionary,
        )
    }

    /** Detects [text]'s language: `sl=auto&tl=en&dt=ld`, reading the source slot. */
    public suspend fun detect(text: String): Pair<String, Float?>? {
        val parsed = TranslateResponseParser.parse(request(text.take(2000), "en", "auto", "en", listOf("t", "ld")))
        val language = parsed.sourceLanguage?.takeIf { it != "und" } ?: return null
        return language to parsed.confidence
    }

    private suspend fun request(text: String, target: String, source: String, ui: String, dt: List<String>): String {
        var networkFailures = 0
        var lastError: GoogleTranslateException? = null
        for (host in hosts) {
            if (host.benchedFor() > Duration.ZERO) continue
            try {
                val response = send(host, text, target, source, ui, dt)
                val body = response.bodyAsText()
                when (val verdict = classify(response.status.value, response.headers["Content-Type"], body)) {
                    Verdict.Ok -> {
                        benchLock.withLock { host.strikes = 0; host.benchedUntil = null }
                        return body
                    }
                    Verdict.Captcha -> {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()?.seconds
                        bench(host, retryAfter)
                        lastError = GoogleTranslateException.RateLimited(host.benchedFor())
                    }
                    Verdict.TryNext -> lastError = GoogleTranslateException.Unavailable("${host.url} answered ${response.status.value}")
                    Verdict.Fail -> throw GoogleTranslateException.Unavailable("${host.url} answered ${response.status.value}: ${body.take(200)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleTranslateException) {
                throw e
            } catch (e: Exception) {
                // A second network failure in a row is the network, not the host.
                lastError = GoogleTranslateException.Network(e)
                if (++networkFailures >= 2) throw lastError
                continue
            }
            networkFailures = 0
        }
        throw lastError ?: GoogleTranslateException.RateLimited(hosts.minOf { it.benchedFor() })
    }

    private suspend fun send(host: Host, text: String, target: String, source: String, ui: String, dt: List<String>): HttpResponse {
        val url = URLBuilder().takeFrom(host.url).apply {
            parameters.append("client", host.client)
            parameters.append("sl", source)
            parameters.append("tl", target)
            parameters.append("hl", ui)
            dt.forEach { parameters.append("dt", it) }
            parameters.append("ie", "UTF-8")
            parameters.append("oe", "UTF-8")
        }
        val common: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            headers {
                append("User-Agent", USER_AGENT)
                append("Accept", "*/*")
                append("Accept-Language", ui)
            }
        }
        // Beyond ~1800 url-encoded bytes the GET is refused (413/400); POST the text instead.
        return if (text.encodeURLParameter().length <= GET_LIMIT) {
            url.parameters.append("q", text)
            http.get(url.build()) { common() }
        } else {
            http.post(url.build()) {
                common()
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build { append("q", text) }))
            }
        }
    }

    private suspend fun bench(host: Host, retryAfter: Duration?) = benchLock.withLock {
        host.strikes++
        val backoff = (30.seconds * (1 shl (host.strikes - 1).coerceAtMost(5))).coerceAtMost(10.minutes)
        val wait = if (retryAfter != null && retryAfter > backoff) retryAfter else backoff
        host.benchedUntil = timeSource.markNow() + wait
        log.w { "${host.url} refused (captcha); benched for $wait" }
    }

    private fun Host.benchedFor(): Duration {
        val until = benchedUntil ?: return Duration.ZERO
        val left = -until.elapsedNow()
        return if (left > Duration.ZERO) left else Duration.ZERO
    }

    private class Host(val url: String, val client: String) {
        var strikes = 0
        var benchedUntil: TimeMark? = null
    }

    internal enum class Verdict { Ok, Captcha, TryNext, Fail }

    internal companion object {
        const val GET_LIMIT = 1800
        val BASIC_DT = listOf("t", "rm", "ld")
        val DICTIONARY_DT = listOf("t", "rm", "ld", "bd", "md", "ex", "ss")
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun safeTld(tld: String): String =
            tld.takeIf { it.length in 2..16 && it.all { c -> c in 'a'..'z' || c == '.' } && !it.startsWith('.') } ?: "com"

        /**
         * Only an HTML page can be a captcha: a JSON reply echoes the source
         * text, which may itself contain "unusual traffic".
         */
        fun classify(status: Int, contentType: String?, body: String): Verdict {
            val html = contentType?.contains("text/html") == true || body.trimStart().startsWith("<")
            val captcha = html && body.take(64 * 1024).let { "/sorry/" in it || "unusual traffic" in it }
            return when {
                captcha || status == 429 -> Verdict.Captcha
                status in 200..299 && !html -> Verdict.Ok
                status == 403 || status >= 500 || html -> Verdict.TryNext
                else -> Verdict.Fail
            }
        }
    }
}

public sealed class GoogleTranslateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Every host is benched after a captcha; try again after [retryAfter]. */
    public class RateLimited(public val retryAfter: Duration) : GoogleTranslateException("Google Translate rate limit, retry in $retryAfter")
    public class Unavailable(message: String) : GoogleTranslateException(message)
    public class Network(cause: Throwable) : GoogleTranslateException("network error: ${cause.message}", cause)
    public class TooLong(length: Int) : GoogleTranslateException("text of $length chars is over the ${TextChunker.MAX_TOTAL} limit")
}

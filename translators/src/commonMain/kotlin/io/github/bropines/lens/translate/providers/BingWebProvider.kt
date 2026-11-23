package io.github.bropines.lens.translate.providers

import io.github.bropines.lens.translate.Capability
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.LanguageMap
import io.github.bropines.lens.translate.Languages
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.Translation
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import io.github.bropines.lens.translate.internal.BROWSER_USER_AGENT
import io.github.bropines.lens.translate.internal.changed
import io.github.bropines.lens.translate.internal.exchange
import io.github.bropines.lens.translate.internal.float
import io.github.bropines.lens.translate.internal.formBody
import io.github.bropines.lens.translate.internal.get
import io.github.bropines.lens.translate.internal.int
import io.github.bropines.lens.translate.internal.sourceCode
import io.github.bropines.lens.translate.internal.statusError
import io.github.bropines.lens.translate.internal.string
import io.github.bropines.lens.translate.internal.targetCode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bing Translator the way its web page does it: load the page for the session
 * parameters (IG, key, token, cookies), then post to `ttranslatev3`. The
 * session is kept in the host's store for 20 minutes; when a request with a
 * stored session fails, the page is loaded again once.
 *
 * The session parameters are what breaks most often here; when the page no
 * longer carries them the error is [ErrorCode.BadResponse].
 */
public object BingWebProvider : TranslationProvider {
    override val id: String = "bing"
    override val name: String = "Bing Translator"
    override val description: String = "Keyless web endpoint. About 1000 characters per request."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect, Capability.Transliteration)

    /** BCP 47, with sr-Cyrl, mn-Cyrl and mww; no be, eo, la, yi or tg. */
    override val languages: LanguageMap = LanguageMap(
        supported = Languages.all - setOf("be", "eo", "la", "yi", "tg"),
        codes = mapOf("sr" to "sr-Cyrl", "mn" to "mn-Cyrl", "hmn" to "mww"),
    )
    override val maxInputChars: Int = 1000

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        val to = targetCode(request)
        val from = sourceCode(request) ?: "auto-detect"
        val stored = storedSession(host)
        return try {
            post(host, stored ?: newSession(host), request.text, from, to)
        } catch (e: TranslationException) {
            // A fresh session that fails will fail again, and a 429 only gets worse with more requests.
            if (stored == null || e.code !in STALE_SESSION_ERRORS) throw e
            host.store.set(SESSION_KEY, null)
            post(host, newSession(host), request.text, from, to)
        }
    }

    private class Session(val ig: String, val key: String, val token: String, val cookie: String)

    private suspend fun storedSession(host: ProviderHost): Session? {
        val json = host.store.get(SESSION_KEY)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: return null
        val at = json["at"].string()?.toLongOrNull() ?: return null
        if (host.nowMillis() - at !in 0..SESSION_TTL_MILLIS) return null
        return Session(
            json["ig"].string() ?: return null,
            json["key"].string() ?: return null,
            json["token"].string() ?: return null,
            json["cookie"].string().orEmpty(),
        )
    }

    private suspend fun newSession(host: ProviderHost): Session {
        val page = host.exchange {
            get(PAGE) {
                header("User-Agent", BROWSER_USER_AGENT)
                header("Accept-Language", "en-US,en;q=0.9")
            }
        }
        if (!page.ok) throw page.failure("the Bing Translator page did not load")
        val ig = IG.find(page.body)?.groupValues?.get(1) ?: throw changed("no IG on the Bing Translator page")
        val helper = ABUSE_HELPER.find(page.body) ?: throw changed("no params_AbusePreventionHelper on the Bing Translator page")
        val cookie = page.headers.getAll("Set-Cookie").orEmpty()
            .map { it.substringBefore(';').trim() }.filter { '=' in it }.joinToString("; ")
        val session = Session(ig, helper.groupValues[1], helper.groupValues[2], cookie)
        host.store.set(
            SESSION_KEY,
            buildJsonObject {
                put("ig", session.ig)
                put("key", session.key)
                put("token", session.token)
                put("cookie", session.cookie)
                put("at", host.nowMillis().toString())
            }.toString(),
        )
        return session
    }

    private suspend fun post(host: ProviderHost, session: Session, text: String, from: String, to: String): Translation {
        val reply = host.exchange {
            post("https://www.bing.com/ttranslatev3") {
                parameter("isVertical", "1")
                parameter("IG", session.ig)
                parameter("IID", "translator.5027")
                header("User-Agent", BROWSER_USER_AGENT)
                header("Origin", "https://www.bing.com")
                header("Referer", PAGE)
                if (session.cookie.isNotEmpty()) header("Cookie", session.cookie)
                formBody(
                    "fromLang" to from,
                    "to" to to,
                    "text" to text,
                    "token" to session.token,
                    "key" to session.key,
                    "tryFetchingGenderDebiasedTranslations" to "true",
                )
            }
        }
        if (!reply.ok) throw reply.failure()
        val json = reply.json()
        if (json !is JsonArray) {
            // An object with statusCode is Bing's way of saying no, with HTTP 200.
            val status = json["statusCode"].int() ?: throw changed("neither translations nor a statusCode")
            throw statusError(status, json["errorMessage"].string().orEmpty().ifEmpty { "Bing refused the request" })
        }
        val first = json[0]
        val translation = first["translations"][0]
        return Translation(
            text = translation["text"].string() ?: throw changed("no translations[0].text"),
            detectedSource = first["detectedLanguage"]["language"].string()?.let(languages::fromProvider),
            confidence = first["detectedLanguage"]["score"].float(),
            transliteration = translation["transliteration"]["text"].string()?.ifBlank { null },
        )
    }

    private const val PAGE = "https://www.bing.com/translator"
    private const val SESSION_KEY = "session"
    private const val SESSION_TTL_MILLIS = 20 * 60 * 1000L
    private val STALE_SESSION_ERRORS = setOf(ErrorCode.Http, ErrorCode.BadResponse, ErrorCode.BadRequest, ErrorCode.NotFound)
    private val IG = Regex("""IG:"([0-9A-Za-z]+)"""")
    private val ABUSE_HELPER = Regex("""params_AbusePreventionHelper\s*=\s*\[\s*(\d+)\s*,\s*"([^"]+)"""")
}

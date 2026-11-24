package io.github.bropines.lens.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.ByteString.Companion.encodeUtf8

/**
 * A Google account's browser session: the cookies of google.com, by name.
 *
 * Optional everywhere. Chromium signs Lens requests in one of two ways
 * (`CreateOAuthHeadersAndContinue` in lens_overlay_query_controller.cc). One
 * is an OAuth token of the browser's own client, which nothing outside Chrome
 * can obtain. The other, "identity delegation"
 * (lens_identity_delegation_helper.cc), is what a cookie session can do: the
 * request carries the user's cookies, `Origin: https://www.google.com`,
 * `X-Goog-AuthUser`, and an `Authorization` header derived from the `SAPISID`
 * cookie and that origin. This class builds the second.
 *
 * The hash itself is the scheme Google's web pages use; Chromium's own
 * `GenerateSapisidHash` was not available to compare byte for byte.
 */
public class GoogleCredentials(cookies: Map<String, String>) {
    public val cookies: Map<String, String> = cookies.filter { (name, value) -> name.isNotBlank() && value.isNotEmpty() }

    /** True when the session can sign a request: it has a session cookie and a `SAPISID`. */
    public val isSignedIn: Boolean
        get() = SESSION_COOKIES.any { it in cookies } && HASH_COOKIES.any { it.second in cookies }

    /** The value of a `Cookie` request header. */
    public val cookieHeader: String get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * The `Authorization` header a Google page at [origin] sends with these
     * cookies at [nowSeconds] (Unix time): one `<scheme> <time>_<sha1>` part
     * for each of `SAPISID` and its first- and third-party twins that is
     * present, or null when there is none.
     */
    public fun authorization(origin: String, nowSeconds: Long): String? =
        HASH_COOKIES.mapNotNull { (scheme, cookie) ->
            val secret = cookies[cookie] ?: return@mapNotNull null
            "$scheme ${nowSeconds}_${"$nowSeconds $secret $origin".encodeUtf8().sha1().hex()}"
        }.takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** The headers that make a request to a Google API a signed-in one, on top of the anonymous ones. */
    public fun headers(nowSeconds: Long, origin: String = ORIGIN): List<Pair<String, String>> = buildList {
        add("Cookie" to cookieHeader)
        authorization(origin, nowSeconds)?.let {
            add("Authorization" to it)
            add("Origin" to origin)
            // The first account of the session; Chromium passes the same index.
            add("X-Goog-AuthUser" to "0")
        }
    }

    public companion object {
        /** The origin Chromium signs Lens requests for: Google's home page. */
        public const val ORIGIN: String = "https://www.google.com"

        private val SESSION_COOKIES = listOf("SID", "__Secure-1PSID", "__Secure-3PSID")
        private val HASH_COOKIES = listOf(
            "SAPISIDHASH" to "SAPISID",
            "SAPISID1PHASH" to "__Secure-1PAPISID",
            "SAPISID3PHASH" to "__Secure-3PAPISID",
        )
    }
}

/**
 * Where a [LensClient] gets the account to sign requests with, and where it
 * says so when Google no longer accepts it.
 */
public interface LensCredentialsSource {
    /** The session to use for the next request, or null to send it anonymously. */
    public fun current(): GoogleCredentials?

    /**
     * Google answered a signed request with [status] (401 or 403): the session
     * has expired or was revoked. The client has already repeated the request
     * anonymously; [current] should stop offering this session.
     */
    public fun rejected(status: Int)
}

/**
 * Reads cookies a person exported from a browser, in whichever form they
 * come: a `Cookie:` header, a Netscape `cookies.txt`, or the JSON of a
 * cookie-editor extension.
 */
public object GoogleCookies {
    /**
     * The Google cookies in [text], by name; empty when it holds none.
     *
     * Where the export says which site a cookie belongs to, only those of
     * google.com and googleapis.com are kept, and of two with one name the one
     * set for all of google.com wins. A bare header says nothing about sites,
     * so all of it is kept.
     */
    public fun parse(text: String): Map<String, String> {
        val trimmed = text.trim().removePrefix("﻿").trim()
        return when {
            trimmed.isEmpty() -> emptyMap()
            trimmed.startsWith("[") || trimmed.startsWith("{") -> fromJson(trimmed)
            trimmed.lineSequence().any { it.count { c -> c == '\t' } >= 6 } -> fromNetscape(trimmed)
            else -> fromHeader(trimmed)
        }
    }

    /** `Cookie: SID=…; HSID=…`, with or without the header's name, on one line or several. */
    public fun fromHeader(text: String): Map<String, String> = buildMap {
        text.lineSequence().flatMap { it.trim().removePrefixIgnoreCase("cookie:").split(';') }.forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (name.isNotEmpty() && isToken(name)) put(name, pair.substringAfter('=').trim())
        }
    }

    /** `domain  flag  path  secure  expiry  name  value`, tab-separated; `#HttpOnly_` marks a cookie, any other `#` a comment. */
    public fun fromNetscape(text: String): Map<String, String> = pick(
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r', '\n').removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t')
            if (fields.size < 7) null else Exported(fields[0], fields[5], fields[6])
        }.toList(),
    )

    /** An array of `{domain, name, value}` objects (EditThisCookie, Cookie-Editor), or an object holding one under `cookies`. */
    public fun fromJson(text: String): Map<String, String> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyMap()
        val list = root as? JsonArray ?: (root as? JsonObject)?.get("cookies") as? JsonArray ?: return emptyMap()
        return pick(
            list.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                Exported(o.text("domain") ?: o.text("host").orEmpty(), o.text("name") ?: return@mapNotNull null, o.text("value") ?: return@mapNotNull null)
            },
        )
    }

    private class Exported(val domain: String, val name: String, val value: String)

    private fun pick(cookies: List<Exported>): Map<String, String> = buildMap {
        // The site-wide cookie last, so it replaces a sub-domain's of the same name.
        cookies.filter { isGoogle(it.domain) && isToken(it.name) && !it.name.startsWith("__Host-") }
            .sortedBy { host(it.domain) == "google.com" }
            .forEach { put(it.name, it.value) }
    }

    private fun host(domain: String): String = domain.trim().lowercase().removePrefix(".")

    /** google.com, googleapis.com and their sub-domains. A `__Host-` cookie is bound to one of them and goes nowhere else. */
    private fun isGoogle(domain: String): Boolean = host(domain).let { h ->
        SITES.any { h == it || h.endsWith(".$it") }
    }

    private val SITES = listOf("google.com", "googleapis.com")

    /** A cookie name is an HTTP token: no spaces, separators or control characters. */
    private fun isToken(name: String): Boolean = name.all { it.code in 0x21..0x7E && it !in "()<>@,;:\\\"/[]?={}" }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

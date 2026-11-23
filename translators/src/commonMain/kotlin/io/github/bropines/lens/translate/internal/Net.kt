package io.github.bropines.lens.translate.internal

import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import io.github.bropines.lens.translate.missingOption
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val BROWSER_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** A response read to the end: providers decide on status and body together. */
internal class Reply(val status: Int, val headers: Headers, val body: String) {
    val ok: Boolean get() = status in 200..299

    /** Only the seconds form of Retry-After; an HTTP date is ignored. */
    val retryAfter: Duration? get() = headers["Retry-After"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

    val isHtml: Boolean get() = headers["Content-Type"]?.contains("text/html") == true || body.trimStart().startsWith("<")

    /** The body as JSON, or [ErrorCode.BadResponse]. */
    fun json(): JsonElement = try {
        Json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw changed("not JSON: ${body.take(120)}")
    }

    /** The body as JSON, or null when it is something else (an HTML error page). */
    fun jsonOrNull(): JsonElement? = try {
        Json.parseToJsonElement(body)
    } catch (e: Exception) {
        null
    }

    /** The error for a status that is not a success, by the common rules. */
    fun failure(detail: String = body.take(200)): TranslationException = statusError(status, detail, retryAfter)
}

internal fun statusError(status: Int, detail: String, retryAfter: Duration? = null): TranslationException {
    val code = when (status) {
        429 -> ErrorCode.RateLimited
        400, 413 -> ErrorCode.BadRequest
        404 -> ErrorCode.NotFound
        else -> ErrorCode.Http
    }
    return TranslationException(code, "HTTP $status: $detail".trim(), retryAfter = retryAfter, httpStatus = status)
}

/** "The service changed its response": what every parser throws instead of crashing. */
internal fun changed(detail: String): TranslationException =
    TranslationException(ErrorCode.BadResponse, "the service changed its response ($detail)")

/**
 * Runs one HTTP exchange and reads the body. Connection problems become
 * [ErrorCode.Network] or [ErrorCode.Timeout]; a status is never an exception
 * here, even when the client has `expectSuccess` on.
 */
internal suspend fun ProviderHost.exchange(block: suspend HttpClient.() -> HttpResponse): Reply = try {
    val response = try {
        http.block()
    } catch (e: ResponseException) {
        e.response
    }
    Reply(response.status.value, response.headers, response.bodyAsText())
} catch (e: CancellationException) {
    throw e
} catch (e: TranslationException) {
    throw e
} catch (e: Exception) {
    val timeout = generateSequence<Throwable>(e) { it.cause }.take(4).any { it::class.simpleName?.contains("Timeout") == true }
    throw TranslationException(
        if (timeout) ErrorCode.Timeout else ErrorCode.Network,
        (if (timeout) "timed out: " else "network error: ") + (e.message ?: e::class.simpleName),
        cause = e,
    )
}

internal fun HttpRequestBuilder.jsonBody(json: JsonElement) {
    setBody(TextContent(json.toString(), ContentType.Application.Json))
}

internal fun HttpRequestBuilder.formBody(vararg fields: Pair<String, String?>) {
    setBody(FormDataContent(Parameters.build { fields.forEach { (name, value) -> if (value != null) append(name, value) } }))
}

// Safe JSON access: a slot that moved or vanished is null, never an exception.
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonElement?.float(): Float? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull

/** A number that some services send as a string ("429"). */
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

/** The engine's code for the request's target, or [ErrorCode.TargetLanguage]. */
internal fun TranslationProvider.targetCode(request: TranslationRequest): String =
    languages.toProvider(request.target, target = true)
        ?: throw TranslationException(ErrorCode.TargetLanguage, "$name does not translate into ${request.target}")

/** The engine's code for the request's source, null to detect, or [ErrorCode.SourceLanguage]. */
internal fun TranslationProvider.sourceCode(request: TranslationRequest): String? = request.source?.let {
    languages.toProvider(it, target = false)
        ?: throw TranslationException(ErrorCode.SourceLanguage, "$name does not translate from $it")
}

/** [ErrorCode.MissingCredential] when a required option is blank. */
internal fun TranslationProvider.requireOptions(host: ProviderHost) {
    missingOption(host)?.let { throw TranslationException(ErrorCode.MissingCredential, "$name: \"${it.label}\" is not set") }
}

internal fun TranslationProvider.rejectedKey(status: Int): TranslationException =
    TranslationException(ErrorCode.MissingCredential, "$name rejected the key (HTTP $status)", httpStatus = status)

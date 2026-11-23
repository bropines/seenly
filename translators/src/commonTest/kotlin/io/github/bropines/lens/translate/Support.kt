package io.github.bropines.lens.translate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val JSON: Headers = headersOf("Content-Type", "application/json; charset=UTF-8")
internal val HTML: Headers = headersOf("Content-Type", "text/html")

/** A host whose HTTP client answers from [handler]. */
internal fun host(
    options: Map<String, String> = emptyMap(),
    store: KeyValueStore = MemoryKeyValueStore(),
    now: () -> Long = { 1_000_000L },
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): SimpleProviderHost = SimpleProviderHost(HttpClient(MockEngine(handler)), options, "en", store, now)

internal fun MockRequestHandleScope.json(body: String, status: Int = 200): HttpResponseData =
    respond(body, HttpStatusCode.fromValue(status), JSON)

internal fun HttpRequestData.form(name: String): String? = (body as FormDataContent).formData[name]
internal fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject
internal fun JsonObject.text(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

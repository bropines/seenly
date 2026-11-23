package io.github.bropines.lens.protocol

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Sends scans to Lens.
 *
 * Owns no HTTP configuration of its own: pass the app's [HttpClient] (with its
 * proxy, timeouts and logging), or use [LensClient.create] for a default one.
 * The client must hand back decompressed bodies: OkHttp does that on its own,
 * and a plain `Accept-Encoding: gzip` header without decoding would not.
 */
public class LensClient(
    private val http: HttpClient,
    private val apiKey: String = LensDefaults.API_KEY,
    private val endpoint: String = LensDefaults.CRUPLOAD_ENDPOINT,
) {
    private val log = Logger.withTag("LensClient")

    /** Prepares [image] (any format the platform decodes) and scans it. */
    public suspend fun scan(image: ByteArray, options: ScanOptions = ScanOptions()): ScanResult {
        // Decoding, scaling and JPEG encoding a 12 MP photo is real CPU work.
        val prepared = withContext(Dispatchers.Default) { ImagePreparer.prepare(image, options.alphaMode) }
        return scan(prepared, options)
    }

    public suspend fun scan(image: PreparedImage, options: ScanOptions = ScanOptions()): ScanResult {
        val body = withContext(Dispatchers.Default) { LensRequests.encode(image, options) }
        log.d { "POST ${body.size} bytes, image ${image.width}x${image.height}, target=${options.targetLanguage}" }

        val response = withContext(Dispatchers.IO) {
            http.post(endpoint) {
                headers { LensRequests.headers(apiKey).forEach { (name, value) -> append(name, value) } }
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) {
            throw LensException.Http(response.status.value, runCatching { response.bodyAsText() }.getOrDefault(""))
        }
        val bytes = response.bodyAsBytes()
        return withContext(Dispatchers.Default) { LensResponses.parse(bytes, image.width, image.height) }
    }

    public companion object {
        /** A client on the platform's default engine (OkHttp on Android and JVM). */
        public fun create(apiKey: String = LensDefaults.API_KEY): LensClient = LensClient(defaultHttpClient(), apiKey)
    }
}

internal expect fun defaultHttpClient(): HttpClient

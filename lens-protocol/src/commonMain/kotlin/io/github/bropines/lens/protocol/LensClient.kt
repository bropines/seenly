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
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    private val clusterInfoEndpoint: String = LensDefaults.CLUSTER_INFO_ENDPOINT,
) {
    private val log = Logger.withTag("LensClient")

    /** Prepares [image] (any format the platform decodes) and scans it. */
    public suspend fun scan(image: ByteArray, options: ScanOptions = ScanOptions()): ScanResult {
        // Decoding, scaling and JPEG encoding a 12 MP photo is real CPU work.
        val prepared = withContext(Dispatchers.Default) { ImagePreparer.prepare(image, options.alphaMode) }
        return scan(prepared, options)
    }

    /**
     * One stateless request: nothing can be searched afterwards. Use
     * [searchSession] when the image may also be searched; its
     * [LensSearchSession.scan] returns the same result from the same upload.
     */
    public suspend fun scan(image: PreparedImage, options: ScanOptions = ScanOptions()): ScanResult {
        val body = withContext(Dispatchers.Default) { LensRequests.encode(image, options) }
        log.d { "image ${image.width}x${image.height}, target=${options.targetLanguage}" }
        val bytes = post(endpoint, body)
        return withContext(Dispatchers.Default) { LensResponses.parse(bytes, image.width, image.height) }
    }

    /**
     * A session for one image: scan it, then search it, on a single upload.
     * It makes no request until its first [LensSearchSession.scan].
     */
    @OptIn(ExperimentalTime::class)
    public fun searchSession(page: SearchPageOptions = SearchPageOptions()): LensSearchSession =
        searchSession(page, Random.Default, { Clock.System.now().toEpochMilliseconds() })

    /** The same with its randomness, clock and codec chosen, so tests can say what goes on the wire. */
    internal fun searchSession(
        page: SearchPageOptions,
        random: Random,
        nowMillis: () -> Long,
        codec: ImageCodec = ImageCodec.Platform,
    ): LensSearchSession =
        LensSearchSession(LensTransport(::post, endpoint, clusterInfoEndpoint), page, random, nowMillis, codec)

    private suspend fun post(url: String, body: ByteArray): ByteArray {
        // The query string is the session id: the path is enough to tell requests apart.
        val path = url.substringBefore('?')
        log.d { "POST $path, ${body.size} bytes" }
        val response = withContext(Dispatchers.IO) {
            http.post(url) {
                headers { LensRequests.headers(apiKey).forEach { (name, value) -> append(name, value) } }
                setBody(body)
            }
        }
        log.d { "HTTP ${response.status.value} from $path" }
        if (!response.status.isSuccess()) {
            throw LensException.Http(response.status.value, runCatching { response.bodyAsText() }.getOrDefault(""))
        }
        return response.bodyAsBytes()
    }

    public companion object {
        /** A client on the platform's default engine (OkHttp on Android and JVM). */
        public fun create(apiKey: String = LensDefaults.API_KEY): LensClient = LensClient(defaultHttpClient(), apiKey)
    }
}

/** What a session needs from a [LensClient]: a way to POST a protobuf, and where to. */
internal class LensTransport(
    val post: suspend (url: String, body: ByteArray) -> ByteArray,
    val cruploadEndpoint: String,
    val clusterInfoEndpoint: String,
)

internal expect fun defaultHttpClient(): HttpClient

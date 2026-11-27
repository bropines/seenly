package io.github.bropines.lens.protocol

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.decodeBase64
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
 *
 * Requests are anonymous unless [credentials] offers a Google session. One
 * that Google refuses (401 or 403) is reported back and the request repeated
 * anonymously, so an expired session never costs a scan. Chromium does the
 * same on a 401 (`ShouldRetryRequestWithoutAuthOn401`).
 */
public class LensClient(
    private val http: HttpClient,
    private val apiKey: String = LensDefaults.API_KEY,
    private val endpoint: String = LensDefaults.CRUPLOAD_ENDPOINT,
    private val clusterInfoEndpoint: String = LensDefaults.CLUSTER_INFO_ENDPOINT,
    private val credentials: LensCredentialsSource? = null,
) {
    private val log = Logger.withTag("LensClient")

    /** Prepares [image] (any format the platform decodes) and scans it. */
    public suspend fun scan(image: ByteArray, options: ScanOptions = ScanOptions()): ScanResult {
        // Decoding, scaling and JPEG encoding a 12 MP photo is real CPU work.
        val prepared = withContext(Dispatchers.Default) { ImagePreparer.prepare(image, options.alphaMode) }
        return scan(prepared, options)
    }

    /**
     * Like [scan], but a tall image (a phone screenshot, a webtoon strip) goes
     * up in full-resolution bands rather than shrunk to 1600 px; see [Tiling].
     * The result covers the whole image either way.
     */
    public suspend fun scanTiled(image: ByteArray, options: ScanOptions = ScanOptions()): ScanResult {
        val raster = withContext(Dispatchers.Default) { ImagePreparer.decode(image) }
        return Tiling.scan(raster) { band ->
            scan(withContext(Dispatchers.Default) { ImagePreparer.prepare(band, options.alphaMode) }, options)
        }
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
        LensSearchSession(
            LensTransport(::post, endpoint, clusterInfoEndpoint, signedIn = { credentials?.current()?.delegates == true }),
            page, random, nowMillis, codec,
        )

    private suspend fun post(url: String, body: ByteArray): ByteArray {
        val account = credentials?.current()
        // Signed the way Chrome's identity delegation signs, a request goes to the clients6 host;
        // anonymous, or repeated without the account, to the usual one.
        val signedUrl = if (account?.delegates == true) url.replace(LensDefaults.API_HOST, LensDefaults.IDENTITY_DELEGATION_HOST) else url
        // The query string is the session id: the path is enough to tell requests apart.
        var path = signedUrl.substringBefore('?')
        log.d { "POST $path, ${body.size} bytes, ${account?.signing ?: "anonymous"}" }
        var response = sendPatiently(signedUrl, body, account, path)
        if (account != null && (response.status.value == 401 || response.status.value == 403)) {
            // Google's error body says why (wrong credential type, origin not allowed, expired…):
            // the one clue there is to how a signed request should look.
            val detail = runCatching { response.bodyAsText() }.getOrDefault("").take(REFUSAL_DETAIL_CHARS)
            log.w { "HTTP ${response.status.value} from $path with the account; repeating without it: $detail" }
            credentials.rejected(response.status.value, detail)
            // A search session opened signed lives on the clients6 host: its id means nothing to the
            // anonymous one. The session starts over anonymously instead (LensSearchSession).
            if (url.contains("gsessionid=")) throw SignedSessionRefused(response.status.value, detail)
            path = url.substringBefore('?')
            log.d { "POST $path, ${body.size} bytes, anonymous" }
            response = sendPatiently(url, body, null, path)
        }
        log.d { "HTTP ${response.status.value} from $path" }
        // The identity delegation host answers in base64, because the request asked it to.
        val wrapped = response.request.url.host == LensDefaults.IDENTITY_DELEGATION_HOST
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            throw LensException.Http(response.status.value, if (wrapped) unwrap(text.encodeToByteArray()).decodeToString() else text)
        }
        val bytes = response.bodyAsBytes()
        return if (wrapped) unwrap(bytes) else bytes
    }

    /** A base64 body decoded; anything that is not base64 (an empty reply, plain text) as it came. */
    private fun unwrap(body: ByteArray): ByteArray =
        body.decodeToString().trim().takeIf { it.isNotEmpty() }?.decodeBase64()?.toByteArray() ?: body

    /**
     * [send], but a request Lens leaves unanswered (a stalled stream, a connection that
     * died under it) is given up after [STALL_MS] and sent once more; a second silence
     * is reported as HTTP 408 rather than waited out.
     */
    private suspend fun sendPatiently(url: String, body: ByteArray, account: GoogleCredentials?, path: String): HttpResponse {
        // Timed on the IO dispatcher: real time, also under a test's virtual clock.
        suspend fun attempt() = withContext(Dispatchers.IO) { withTimeoutOrNull(STALL_MS) { send(url, body, account) } }
        attempt()?.let { return it }
        log.w { "no answer from $path in ${STALL_MS / 1000} s; sending it again" }
        return attempt() ?: throw LensException.Http(408, "Lens did not answer")
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun send(url: String, body: ByteArray, account: GoogleCredentials?): HttpResponse = withContext(Dispatchers.IO) {
        val signed = LensRequests.headers(apiKey, account, Clock.System.now().epochSeconds)
        http.post(url) {
            headers { signed.forEach { (name, value) -> append(name, value) } }
            setBody(body)
        }
    }

    public companion object {
        private const val REFUSAL_DETAIL_CHARS = 600
        /** How long a request may go without an answer: an upload normally takes one to three seconds. */
        private const val STALL_MS = 25_000L

        /** A client on the platform's default engine (OkHttp on Android and JVM). */
        public fun create(apiKey: String = LensDefaults.API_KEY): LensClient = LensClient(defaultHttpClient(), apiKey)
    }
}

/** What a session needs from a [LensClient]: a way to POST a protobuf, where to, and whether it goes signed. */
internal class LensTransport(
    val post: suspend (url: String, body: ByteArray) -> ByteArray,
    val cruploadEndpoint: String,
    val clusterInfoEndpoint: String,
    /** Whether the next request goes signed (to the identity delegation host). */
    val signedIn: () -> Boolean = { false },
)

/**
 * Google refused a signed request that belongs to a search session opened
 * signed. The session cannot go on anonymously: its ids are the signed host's.
 */
internal class SignedSessionRefused(val status: Int, detail: String) :
    Exception("HTTP $status for a signed search session: $detail")

internal expect fun defaultHttpClient(): HttpClient

package io.github.bropines.lens.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lens.LensOverlayRequestId
import lens.LensOverlayRoutingInfo
import lens.LensOverlayServerClusterInfoResponse
import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/** What a region search gives back. */
public class RegionSearch(
    /** The results page for the region. */
    public val url: String,
    /**
     * The region's text, read again by Lens from the crop that was sent, or
     * null when it sent none. Its boxes are normalised to the whole uploaded
     * image, like the scan's, not to the region (seen live: the request's
     * `zoomed_crop` tells Lens where the crop sits).
     */
    public val text: ScanResult?,
)

/** The JPEG codec a session crops with; a seam so tests need no real images. */
internal interface ImageCodec {
    fun decode(bytes: ByteArray): Raster
    fun encodeJpeg(opaque: Raster): PreparedImage

    object Platform : ImageCodec {
        override fun decode(bytes: ByteArray): Raster = ImagePreparer.decode(bytes)
        override fun encodeJpeg(opaque: Raster): PreparedImage = ImagePreparer.encode(opaque)
    }
}

/**
 * One image, uploaded once, then read and searched.
 *
 * Lens is two things stitched together: a protobuf API that describes the
 * image (text, translation, detected objects) and an ordinary Google results
 * page that does the searching. This class does the stitching the way
 * Chromium's `LensOverlayQueryController` does:
 *
 * 1. opens a session (`/v1/gsessionid`), which hands out a *server* session
 *    id for uploads and a *search* session id for result URLs;
 * 2. uploads the image in an objects request, which is [scan];
 * 3. for each search, sends an interaction request saying what was selected,
 *    and builds the results URL from the next request id in the sequence.
 *
 * Every function that returns a URL means it to be opened **in the user's own
 * browser**. Nothing else renders it: see [LensSearchUrls].
 *
 * A session lasts [LensDefaults.SESSION_LIFETIME_SECONDS]. A search on an
 * expired one reopens it and uploads the image again without the caller
 * noticing; the one thing that does not survive is [DetectedObject.id], which
 * belongs to the upload it came from, so scan again before a late object
 * search if that matters.
 *
 * Safe to call from several coroutines: requests go out one at a time,
 * because their sequence numbers are a counter.
 */
public class LensSearchSession internal constructor(
    private val transport: LensTransport,
    private val page: SearchPageOptions,
    private val random: Random,
    private val nowMillis: () -> Long,
    private val codec: ImageCodec = ImageCodec.Platform,
) {
    private class Cluster(
        val serverSessionId: String,
        val searchSessionId: String,
        /** Opaque here; copied into every request id, as Chromium does. */
        val routingInfo: LensOverlayRoutingInfo?,
        val openedAtMillis: Long,
        /** Opened signed, on the identity delegation host: its ids are good only there. */
        val signed: Boolean,
    )

    private class Upload(val image: PreparedImage, val options: ScanOptions, var raster: Raster?)

    private val mutex = Mutex()
    private var upload: Upload? = null
    private var cluster: Cluster? = null
    private var uuid = 0L
    private var contextId = 0L
    private var sequence = 0

    /**
     * Prepares [image] (any format the platform decodes), opens a fresh
     * session and uploads it. The result is what [LensClient.scan] returns;
     * the difference is that the upload can now be searched.
     *
     * Calling it again, with the same image or another, starts over: a new
     * session, a new upload, and searches from then on are about that one.
     */
    public suspend fun scan(image: ByteArray, options: ScanOptions = ScanOptions()): ScanResult {
        val (prepared, raster) = withContext(Dispatchers.Default) {
            val raster = ImagePreparer.uploadRaster(ImagePreparer.decode(image), options.alphaMode)
            ImagePreparer.encode(raster) to raster
        }
        // Regions are cut from these pixels rather than from the JPEG made of them.
        val upload = Upload(prepared, options, raster)
        return mutex.withLock { anonymousOnRefusal { start(upload) } }
    }

    public suspend fun scan(image: PreparedImage, options: ScanOptions = ScanOptions()): ScanResult {
        val upload = Upload(image, options, raster = null)
        return mutex.withLock { anonymousOnRefusal { start(upload) } }
    }

    /** Visual matches for the whole image. */
    public suspend fun searchImageUrl(): String = searchRegion(Region.Whole).url

    /**
     * Results for [region] of the image: visual matches, or image plus words
     * when [text] is given.
     */
    public suspend fun searchRegionUrl(region: Region, text: String? = null): String = searchRegion(region, text).url

    /**
     * [searchRegionUrl], and the region's text as Lens read it from the crop.
     *
     * @throws IllegalArgumentException when no part of [region] is inside the image.
     */
    public suspend fun searchRegion(region: Region, text: String? = null): RegionSearch =
        regionSearch(region, text) { searchSessionId, urlId ->
            LensSearchUrls.visual(searchSessionId, urlId, text, page)
        }

    /** Results for an object from [ScanResult.objects] of this session's scan. */
    public suspend fun searchObjectUrl(objectId: String, text: String? = null): String = mutex.withLock {
        anonymousOnRefusal {
            val (cluster, upload) = ready()
            interaction(cluster, { id -> LensRequests.objectRequest(id, upload.options, objectId, text).encode() }) { urlId ->
                LensSearchUrls.visual(cluster.searchSessionId, urlId, text, page)
            }.first
        }
    }

    /** AI Mode with the image, or just [region] of it, attached and [question] asked about it. */
    public suspend fun askAiUrl(question: String, region: Region? = null): String {
        require(question.isNotBlank()) { "AI Mode needs a question" }
        return regionSearch(region ?: Region.Whole, question) { searchSessionId, urlId ->
            LensSearchUrls.aiMode(searchSessionId, urlId, question, page)
        }.url
    }

    /** A plain search for [text]. Needs no upload, and makes no request. */
    public fun searchTextUrl(text: String): String = LensSearchUrls.text(text, page)

    /**
     * The math solver for a formula Lens recognised: [latex] is [Word.latex],
     * [text] what to show in the search box. Needs no upload, and makes no request.
     */
    public fun solveFormulaUrl(latex: String, text: String = latex): String = LensSearchUrls.formula(latex, text, page)

    private suspend fun regionSearch(
        region: Region,
        text: String?,
        url: (searchSessionId: String, urlId: LensOverlayRequestId) -> String,
    ): RegionSearch {
        val inside = region.clamped()
        require(inside.width > 0f && inside.height > 0f) { "$region is outside the image" }
        return mutex.withLock {
          anonymousOnRefusal {
            val (cluster, upload) = ready()
            val whole = inside == Region.Whole
            val crop = if (whole) upload.image else withContext(Dispatchers.Default) { crop(upload, inside) }
            val (address, reply) = interaction(
                cluster,
                { id ->
                    LensRequests.regionRequest(id, upload.options, inside, crop, upload.image.width, upload.image.height, text)
                        .encode()
                },
            ) { urlId -> url(cluster.searchSessionId, urlId) }
            // The whole image read again is the scan the caller already has.
            RegionSearch(address, if (whole) null else LensResponses.parseInteraction(reply, upload.image.width, upload.image.height))
          }
        }
    }

    /**
     * Runs [block]; when Google refuses the account in the middle of a session
     * opened signed, forgets that session and runs [block] once more, which
     * opens and uploads into an anonymous one. A second refusal is an error.
     */
    private suspend fun <T> anonymousOnRefusal(block: suspend () -> T): T = try {
        block()
    } catch (e: SignedSessionRefused) {
        cluster = null
        try {
            block()
        } catch (again: SignedSessionRefused) {
            throw LensException.Http(again.status, again.message.orEmpty())
        }
    }

    private fun crop(upload: Upload, region: Region): PreparedImage {
        val raster = upload.raster ?: codec.decode(upload.image.jpeg).also { upload.raster = it }
        return codec.encodeJpeg(raster.crop(region.toPixels(raster.width, raster.height)))
    }

    /** The session to send into, reopened first if it has run out. */
    private suspend fun ready(): Pair<Cluster, Upload> {
        val upload = checkNotNull(upload) { "nothing uploaded yet: call scan() first" }
        val lifetime = (LensDefaults.SESSION_LIFETIME_SECONDS - LensDefaults.SESSION_MARGIN_SECONDS) * 1000
        val current = cluster
        // A session opened signed is no use once requests go anonymous (the account was refused or removed).
        val stale = current != null && current.signed && !transport.signedIn()
        if (current == null || stale || nowMillis() - current.openedAtMillis >= lifetime) start(upload)
        return checkNotNull(cluster) to upload
    }

    /**
     * Opens a session and uploads into it. Nothing is kept until the upload
     * has succeeded: a session with no image in it would mint URLs that show
     * nothing.
     */
    private suspend fun start(upload: Upload): ScanResult {
        val opened = openCluster()
        // Non-negative, like the stateless request's.
        val uuid = random.nextLong() ushr 1
        val contextId = random.nextLong() ushr 1
        val id = requestId(opened, uuid, contextId, sequence = 1, analyticsId = random.nextBytes(16))
        val body = withContext(Dispatchers.Default) {
            LensRequests.objectsRequest(upload.image, upload.options, id).encode()
        }
        val reply = transport.post(crupload(opened), body)
        val result = withContext(Dispatchers.Default) {
            LensResponses.parse(reply, upload.image.width, upload.image.height)
        }
        this.cluster = opened
        this.upload = upload
        this.uuid = uuid
        this.contextId = contextId
        this.sequence = 1
        return result
    }

    private suspend fun openCluster(): Cluster {
        val reply = transport.post(transport.clusterInfoEndpoint, LensRequests.clusterInfoRequest().encode())
        val info = try {
            LensOverlayServerClusterInfoResponse.ADAPTER.decode(reply)
        } catch (e: Exception) {
            throw LensException.MalformedResponse("not a cluster info response", e)
        }
        if (info.server_session_id.isEmpty() || info.search_session_id.isEmpty()) {
            throw LensException.MalformedResponse("Lens did not open a search session")
        }
        // Asked after the request: had the account been refused, it was repeated anonymously.
        return Cluster(info.server_session_id, info.search_session_id, info.routing_info, nowMillis(), signed = transport.signedIn())
    }

    /**
     * Sends one interaction and returns the URL for it with the reply.
     *
     * The numbering is `LensOverlayRequestIdGenerator`'s: the interaction
     * takes the next sequence id and a new analytics id; the URL takes the one
     * after, with the same analytics id (`RequestIdUpdateMode::kSearchUrl`).
     *
     * The URL exists as soon as the interaction is sent, as in Chromium, but
     * is handed out only once Lens has answered: the page resolves `vsrid`
     * against the interaction, and opening it sooner would race the request
     * it names.
     */
    private suspend fun interaction(
        cluster: Cluster,
        body: (LensOverlayRequestId) -> ByteArray,
        url: (LensOverlayRequestId) -> String,
    ): Pair<String, ByteArray> {
        val analyticsId = random.nextBytes(16)
        val request = body(requestId(cluster, uuid, contextId, ++sequence, analyticsId))
        val address = url(requestId(cluster, uuid, contextId, ++sequence, analyticsId))
        val reply = transport.post(crupload(cluster), request)
        // An error here means Lens did not take the selection, and the URL would show nothing.
        LensResponses.checkError(reply)
        return address to reply
    }

    private fun requestId(cluster: Cluster, uuid: Long, contextId: Long, sequence: Int, analyticsId: ByteArray) =
        LensOverlayRequestId(
            uuid = uuid,
            sequence_id = sequence,
            // One image per session, so this never moves.
            image_sequence_id = 1,
            analytics_id = analyticsId.toByteString(),
            // The initial request bumps long_context_id to 1 and nothing after it does.
            long_context_id = 1,
            routing_info = cluster.routingInfo,
            time_usec = nowMillis() * 1000,
            context_id = contextId,
        )

    /** `crupload` takes the *server* session id; the search one goes on result URLs. */
    private fun crupload(cluster: Cluster): String =
        "${transport.cruploadEndpoint}?gsessionid=${LensSearchUrls.percentEncode(cluster.serverSessionId)}"
}

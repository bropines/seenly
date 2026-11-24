package io.github.bropines.lens.protocol

import lens.AppliedFilter
import lens.AppliedFilters
import lens.CenterRotatedBox
import lens.ClientImage
import lens.CoordinateType
import lens.ImageCrop
import lens.ImageData
import lens.ImageMetadata
import lens.ImagePayload
import lens.LensOverlayClientContext
import lens.LensOverlayFilterType
import lens.LensOverlayInteractionRequest
import lens.LensOverlayInteractionRequestMetadata
import lens.LensOverlayObjectsRequest
import lens.LensOverlayRequestContext
import lens.LensOverlayRequestId
import lens.LensOverlayServerClusterInfoRequest
import lens.LensOverlayServerRequest
import lens.LensRenderingEnvironment
import lens.LocaleContext
import lens.Platform
import lens.RenderingContext
import lens.Surface
import lens.TextQuery
import lens.ZoomedCrop
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** An image ready for upload: JPEG bytes plus the size they decode to. */
public class PreparedImage(public val jpeg: ByteArray, public val width: Int, public val height: Int)

/** Per-request identity. Random by default; fixed in tests so the bytes are comparable. */
public data class RequestIdentity(val uuid: Long, val timeMicros: Long) {
    public companion object {
        @OptIn(ExperimentalTime::class)
        public fun random(): RequestIdentity {
            val now = Clock.System.now()
            // Non-negative, like Chromium's base::RandUint64() >> 1.
            return RequestIdentity(Random.nextLong() ushr 1, now.epochSeconds * 1_000_000 + now.nanosecondsOfSecond / 1000)
        }
    }
}

/**
 * Builds the `crupload` request Chromium's Lens overlay sends, so a caller with
 * its own HTTP stack needs nothing else from this library but [LensResponses].
 */
public object LensRequests {
    public fun objectsRequest(
        image: PreparedImage,
        options: ScanOptions = ScanOptions(),
        identity: RequestIdentity = RequestIdentity.random(),
    ): LensOverlayServerRequest = objectsRequest(
        image,
        options,
        LensOverlayRequestId(
            uuid = identity.uuid,
            sequence_id = 1,
            image_sequence_id = 1,
            time_usec = identity.timeMicros,
            // mime_type stays unset: Chromium states it only for
            // WebP with alpha, and this always uploads JPEG.
        ),
    )

    /** The objects request under a caller-made request id, as a search session sends it. */
    public fun objectsRequest(
        image: PreparedImage,
        options: ScanOptions,
        requestId: LensOverlayRequestId,
    ): LensOverlayServerRequest = LensOverlayServerRequest(
        objects_request = LensOverlayObjectsRequest(
            request_context = LensOverlayRequestContext(request_id = requestId, client_context = clientContext(options)),
            image_data = ImageData(
                payload = ImagePayload(image_bytes = image.jpeg.toByteString()),
                image_metadata = ImageMetadata(width = image.width, height = image.height),
            ),
        ),
    )

    /** Asks for the session ids; `enable_search_session_id` is what makes the reply usable for a results URL. */
    public fun clusterInfoRequest(): LensOverlayServerClusterInfoRequest = LensOverlayServerClusterInfoRequest(
        enable_search_session_id = true,
        surface = Surface.SURFACE_CHROMIUM,
        platform = Platform.PLATFORM_WEB,
        rendering_context = RenderingContext(
            rendering_environment = LensRenderingEnvironment.RENDERING_ENV_LENS_OVERLAY,
        ),
    )

    /**
     * REGION_SEARCH: "search what is inside this box". [crop] is the box's own
     * pixels, and [parentWidth] x [parentHeight] the uploaded image it was cut
     * from. A [text] query makes the search multimodal.
     *
     * The client context never asks for a translation here, whatever the
     * upload asked for: an interaction states a selection, not a filter.
     */
    public fun regionRequest(
        requestId: LensOverlayRequestId,
        options: ScanOptions,
        region: Region,
        crop: PreparedImage,
        parentWidth: Int,
        parentHeight: Int,
        text: String? = null,
    ): LensOverlayServerRequest = LensOverlayServerRequest(
        interaction_request = LensOverlayInteractionRequest(
            request_context = interactionContext(requestId, options),
            interaction_request_metadata = LensOverlayInteractionRequestMetadata(
                type = LensOverlayInteractionRequestMetadata.Type.REGION_SEARCH,
                selection_metadata = LensOverlayInteractionRequestMetadata.SelectionMetadata(
                    region = LensOverlayInteractionRequestMetadata.SelectionMetadata.Region(region = region.toProto()),
                ),
                query_metadata = queryMetadata(text),
            ),
            image_crop = ImageCrop(
                image = ClientImage(image_content = crop.jpeg.toByteString()),
                zoomed_crop = ZoomedCrop(
                    crop = region.toProto(),
                    parent_width = parentWidth,
                    parent_height = parentHeight,
                    zoom = 1f,
                ),
            ),
        ),
    )

    /** TAP: "search this object", by an id from the objects response of the same upload. */
    public fun objectRequest(
        requestId: LensOverlayRequestId,
        options: ScanOptions,
        objectId: String,
        text: String? = null,
    ): LensOverlayServerRequest = LensOverlayServerRequest(
        interaction_request = LensOverlayInteractionRequest(
            request_context = interactionContext(requestId, options),
            interaction_request_metadata = LensOverlayInteractionRequestMetadata(
                type = LensOverlayInteractionRequestMetadata.Type.TAP,
                selection_metadata = LensOverlayInteractionRequestMetadata.SelectionMetadata(
                    object_ = LensOverlayInteractionRequestMetadata.SelectionMetadata.Object(object_id = objectId),
                ),
                query_metadata = queryMetadata(text),
            ),
        ),
    )

    private fun interactionContext(requestId: LensOverlayRequestId, options: ScanOptions) = LensOverlayRequestContext(
        request_id = requestId,
        client_context = clientContext(options.copy(targetLanguage = null, sourceLanguage = null)),
    )

    private fun queryMetadata(text: String?) = text?.trim()?.takeIf { it.isNotEmpty() }?.let {
        LensOverlayInteractionRequestMetadata.QueryMetadata(text_query = TextQuery(query = it))
    }

    private fun Region.toProto() = CenterRotatedBox(
        center_x = centerX,
        center_y = centerY,
        width = width,
        height = height,
        coordinate_type = CoordinateType.NORMALIZED,
    )

    private fun clientContext(options: ScanOptions): LensOverlayClientContext {
        // Chromium sends exactly one filter, and TRANSLATE replaces AUTO_FILTER
        // rather than joining it.
        val filter = when (val target = options.targetLanguage) {
            null -> AppliedFilter(filter_type = LensOverlayFilterType.AUTO_FILTER)
            else -> AppliedFilter(
                filter_type = LensOverlayFilterType.TRANSLATE,
                translate = AppliedFilter.Translate(
                    target_language = target,
                    source_language = options.sourceLanguage,
                ),
            )
        }

        return LensOverlayClientContext(
            // Chromium's shipping path: SURFACE_CHROMIUM/PLATFORM_WEB with the
            // lens-overlay rendering environment. It never sets app_id.
            platform = Platform.PLATFORM_WEB,
            surface = Surface.SURFACE_CHROMIUM,
            locale_context = LocaleContext(
                language = options.ocrLanguage,
                region = options.region,
                time_zone = options.timeZone,
            ),
            client_filters = AppliedFilters(filter = listOf(filter)),
            rendering_context = RenderingContext(
                rendering_environment = LensRenderingEnvironment.RENDERING_ENV_LENS_OVERLAY,
            ),
        )
    }

    public fun encode(
        image: PreparedImage,
        options: ScanOptions = ScanOptions(),
        identity: RequestIdentity = RequestIdentity.random(),
    ): ByteArray = objectsRequest(image, options, identity).encode()

    /**
     * The headers Chromium sends. Transport headers (`Accept-Encoding`,
     * `Content-Length`) belong to the HTTP client: one that advertises gzip
     * without decompressing hands back bytes that fail to parse.
     *
     * With [credentials] the request is also signed in as that account at
     * [nowSeconds] (Unix time), the way a Google web page signs its API calls;
     * without them it is exactly the anonymous request.
     */
    public fun headers(
        apiKey: String = LensDefaults.API_KEY,
        credentials: GoogleCredentials? = null,
        nowSeconds: Long = 0,
    ): List<Pair<String, String>> = listOf(
        "Content-Type" to "application/x-protobuf",
        "Accept" to "*/*",
        "User-Agent" to LensDefaults.USER_AGENT,
        "X-Goog-Api-Key" to apiKey,
    ) + credentials?.headers(nowSeconds).orEmpty()
}

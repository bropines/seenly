package io.github.bropines.lens.protocol

import lens.AppliedFilter
import lens.AppliedFilters
import lens.ImageData
import lens.ImageMetadata
import lens.ImagePayload
import lens.LensOverlayClientContext
import lens.LensOverlayFilterType
import lens.LensOverlayObjectsRequest
import lens.LensOverlayRequestContext
import lens.LensOverlayRequestId
import lens.LensOverlayServerRequest
import lens.LensRenderingEnvironment
import lens.LocaleContext
import lens.Platform
import lens.RenderingContext
import lens.Surface
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
    ): LensOverlayServerRequest {
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

        val clientContext = LensOverlayClientContext(
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

        return LensOverlayServerRequest(
            objects_request = LensOverlayObjectsRequest(
                request_context = LensOverlayRequestContext(
                    request_id = LensOverlayRequestId(
                        uuid = identity.uuid,
                        sequence_id = 1,
                        image_sequence_id = 1,
                        time_usec = identity.timeMicros,
                        // mime_type stays unset: Chromium states it only for
                        // WebP with alpha, and this always uploads JPEG.
                    ),
                    client_context = clientContext,
                ),
                image_data = ImageData(
                    payload = ImagePayload(image_bytes = image.jpeg.toByteString()),
                    image_metadata = ImageMetadata(width = image.width, height = image.height),
                ),
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
     */
    public fun headers(apiKey: String = LensDefaults.API_KEY): List<Pair<String, String>> = listOf(
        "Content-Type" to "application/x-protobuf",
        "Accept" to "*/*",
        "User-Agent" to LensDefaults.USER_AGENT,
        "X-Goog-Api-Key" to apiKey,
    )
}

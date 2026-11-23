package io.github.bropines.lens.protocol

import kotlinx.serialization.Serializable
import lens.EducationInputExtension
import lens.LensOverlayRequestId
import lens.MathSolverQuery
import lens.StickinessSignals
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A rectangle inside an image, normalised to it: `left`/`width` are fractions
 * of the image's width, `top`/`height` of its height. Unrotated, unlike [Box].
 */
@Serializable
public data class Region(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2
    val centerY: Float get() = top + height / 2

    /** The part of this region that is inside the image, so a drag past the edge still means something. */
    public fun clamped(): Region {
        val x0 = left.coerceIn(0f, 1f)
        val y0 = top.coerceIn(0f, 1f)
        val x1 = right.coerceIn(0f, 1f)
        val y1 = bottom.coerceIn(0f, 1f)
        return Region(x0, y0, maxOf(0f, x1 - x0), maxOf(0f, y1 - y0))
    }

    /**
     * The pixels of a [imageWidth] x [imageHeight] image this region touches:
     * clamped to the image, grown outwards to whole pixels, never empty.
     */
    public fun toPixels(imageWidth: Int, imageHeight: Int): PixelRect {
        require(imageWidth > 0 && imageHeight > 0) { "bad image size ${imageWidth}x$imageHeight" }
        val inside = clamped()
        val x0 = floor(inside.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val y0 = floor(inside.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val x1 = ceil(inside.right * imageWidth).toInt().coerceIn(x0 + 1, imageWidth)
        val y1 = ceil(inside.bottom * imageHeight).toInt().coerceIn(y0 + 1, imageHeight)
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    public companion object {
        /** The whole image. */
        public val Whole: Region = Region(0f, 0f, 1f, 1f)

        /** The upright rectangle around [box]'s centre and size; its rotation is dropped. */
        public fun of(box: Box): Region = Region(box.left, box.top, box.width, box.height)
    }
}

/** A rectangle in whole pixels, origin top left. */
public data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Cuts [rect] out of this raster. */
internal fun Raster.crop(rect: PixelRect): Raster {
    require(rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height) {
        "$rect is outside ${width}x$height"
    }
    val out = IntArray(rect.width * rect.height)
    for (row in 0 until rect.height) {
        val from = (rect.y + row) * width + rect.x
        argb.copyInto(out, row * rect.width, from, from + rect.width)
    }
    return Raster(rect.width, rect.height, out)
}

/** How the results page should look. Both are left off the URL when null, and Google decides. */
public data class SearchPageOptions(
    /** `hl`: the page's language, e.g. `"ru"`. */
    val uiLanguage: String? = null,
    /** `cs`: dark or light. */
    val darkMode: Boolean? = null,
)

/**
 * The results-page URLs Chromium's `lens_overlay_url_builder.cc` builds.
 *
 * Nothing a user calls "search" is in a Lens response: visual matches,
 * products and the AI answer are HTML on `www.google.com/search`. An upload
 * and a page are tied together by two ids on the URL: `vsrid`, a serialised
 * request id that names the upload, and `gsessionid`, the *search* session id.
 * Google joins them on its side and does not care which client uploaded.
 *
 * The page must open in the user's own browser. Fetched by a script, a
 * headless browser or a fresh profile it answers with a consent page or a
 * CAPTCHA; do not try to get past either.
 */
public object LensSearchUrls {
    /**
     * Results for an uploaded image (`BuildLensSearchURL`): visual matches when
     * [text] is blank (`udm=26`), image plus words when it is not (`udm=24`).
     *
     * [searchSessionId] is the cluster info's `search_session_id`. The
     * *server* one belongs on `crupload` only.
     */
    public fun visual(
        searchSessionId: String,
        requestId: LensOverlayRequestId,
        text: String? = null,
        page: SearchPageOptions = SearchPageOptions(),
    ): String {
        val query = text?.trim().orEmpty()
        return lens(searchSessionId, requestId, query, udm = if (query.isEmpty()) "26" else "24", page)
    }

    /** AI Mode with the uploaded image attached and [question] asked about it (`udm=50`). */
    public fun aiMode(
        searchSessionId: String,
        requestId: LensOverlayRequestId,
        question: String,
        page: SearchPageOptions = SearchPageOptions(),
    ): String = lens(searchSessionId, requestId, question.trim(), udm = "50", page)

    /**
     * A plain search for words read off an image (`BuildTextOnlySearchURL`).
     * No upload and no session: the `lns_*` parameters only say where the
     * query came from.
     */
    public fun text(text: String, page: SearchPageOptions = SearchPageOptions()): String =
        build(textParameters(text) + pageParameters(page))

    /**
     * The text-only URL with the math solver's stickiness signal
     * (`AppendStickinessSignalForFormula`): [latex] is the equation, from
     * [Word.latex], and [text] the visible query.
     *
     * The `stick` value holds gzip bytes, whose header differs between
     * platforms, so the same input does not give the same string everywhere.
     */
    public fun formula(latex: String, text: String = latex, page: SearchPageOptions = SearchPageOptions()): String {
        val signals = StickinessSignals(
            id_namespace = StickinessSignals.Namespace.EDUCATION_INPUT,
            education_input_extension = EducationInputExtension(
                math_solver_query = MathSolverQuery(math_input_equation = latex),
            ),
        )
        return build(textParameters(text) + pageParameters(page) + ("stick" to base64Url(gzip(signals.encode()))))
    }

    /** `vsrid`: the request id as base64url without padding. */
    public fun vsrid(requestId: LensOverlayRequestId): String = base64Url(requestId.encode())

    private fun lens(
        searchSessionId: String,
        requestId: LensOverlayRequestId,
        query: String,
        udm: String,
        page: SearchPageOptions,
    ): String {
        // AI Mode is multimodal even with nothing typed: the image is the other half.
        val multimodal = query.isNotEmpty() || udm == "50"
        return build(
            listOf(
                "q" to query,
                "lns_mode" to if (multimodal) "mu" else "un",
                "lns_fp" to "1",
                "lns_surface" to "42",
                "gsessionid" to searchSessionId,
                "udm" to udm,
                "vsrid" to vsrid(requestId),
                // The image context menu's entry point. The logging parameters
                // (vsint, plla, qsubts, cud) are left out: the page renders without them.
                "source" to "chrome.cr.ctxi",
            ) + pageParameters(page),
        )
    }

    private fun textParameters(text: String) =
        listOf("q" to text.trim(), "lns_fp" to "1", "lns_mode" to "text", "lns_surface" to "42")

    private fun pageParameters(page: SearchPageOptions) = listOfNotNull(
        page.uiLanguage?.takeIf { it.isNotEmpty() }?.let { "hl" to it },
        page.darkMode?.let { "cs" to if (it) "1" else "0" },
    )

    private fun build(parameters: List<Pair<String, String>>): String =
        LensDefaults.RESULTS_URL + "?" + parameters.joinToString("&") { (name, value) -> "$name=${percentEncode(value)}" }

    private fun base64Url(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** RFC 3986 unreserved characters as they are, every other UTF-8 byte as %XX. */
    internal fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%')
                append("0123456789ABCDEF"[byte.toInt() shr 4 and 0xF])
                append("0123456789ABCDEF"[byte.toInt() and 0xF])
            }
        }
    }
}

/** RFC 1952 gzip, as Chromium's `compression::GzipCompress`. */
internal expect fun gzip(bytes: ByteArray): ByteArray

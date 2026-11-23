package io.github.bropines.lens.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import lens.CenterRotatedBox
import lens.CoordinateType
import lens.Geometry
import lens.LensOverlayFilterType
import lens.LensOverlayInteractionRequestMetadata
import lens.LensOverlayInteractionResponse
import lens.LensOverlayObjectsResponse
import lens.LensOverlayRequestId
import lens.LensOverlayRoutingInfo
import lens.LensOverlayServerClusterInfoRequest
import lens.LensRenderingEnvironment
import lens.LensOverlayServerClusterInfoResponse
import lens.LensOverlayServerError
import lens.LensOverlayServerRequest
import lens.LensOverlayServerResponse
import lens.OverlayObject
import lens.Platform
import lens.RenderingContext
import lens.Surface
import lens.Text
import lens.TextLayout
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test

class LensSearchSessionTest {
    private val image = PreparedImage(byteArrayOf(9, 8, 7), 4, 2)
    private val routing = LensOverlayRoutingInfo(server_address = "addr", cell_address = "cell", blade_target = "blade")

    /** A Lens that opens sessions `srv-1`/`sch-1`, `srv-2`/`sch-2`, ... and remembers what it was sent. */
    private class FakeLens(
        val routing: LensOverlayRoutingInfo,
        val clusterReply: (Int) -> LensOverlayServerClusterInfoResponse? = { null },
        val interactionReply: LensOverlayServerResponse =
            LensOverlayServerResponse(interaction_response = LensOverlayInteractionResponse(encoded_response = "x")),
    ) {
        val urls = mutableListOf<String>()
        val clusterRequests = mutableListOf<LensOverlayServerClusterInfoRequest>()
        val uploads = mutableListOf<LensOverlayServerRequest>()
        var now = 1_000_000L
        private var sessions = 0

        val engine = MockEngine { request ->
            urls += request.url.toString()
            val body = request.body.toByteArray()
            if (request.url.encodedPath.endsWith("/gsessionid")) {
                clusterRequests += LensOverlayServerClusterInfoRequest.ADAPTER.decode(body)
                val n = ++sessions
                val reply = clusterReply(n) ?: LensOverlayServerClusterInfoResponse(
                    server_session_id = "srv-$n",
                    search_session_id = "sch-$n",
                    routing_info = routing,
                )
                respond(reply.encode(), HttpStatusCode.OK)
            } else {
                val sent = LensOverlayServerRequest.ADAPTER.decode(body)
                uploads += sent
                val reply = if (sent.objects_request != null) {
                    LensOverlayServerResponse(
                        objects_response = LensOverlayObjectsResponse(
                            overlay_objects = listOf(OverlayObject(id = "obj-1", geometry = box(0.5f, 0.5f, 0.2f, 0.4f))),
                        ),
                    )
                } else {
                    interactionReply
                }
                respond(reply.encode(), HttpStatusCode.OK)
            }
        }

        fun session(
            seed: Int = 7,
            page: SearchPageOptions = SearchPageOptions(),
            codec: ImageCodec = NoCodec,
        ): LensSearchSession =
            LensClient(HttpClient(engine), endpoint = CRUPLOAD, clusterInfoEndpoint = CLUSTER)
                .searchSession(page, Random(seed), { now }, codec)

        fun ids(): List<LensOverlayRequestId> = uploads.map {
            (it.objects_request?.request_context ?: it.interaction_request!!.request_context)!!.request_id!!
        }
    }

    private object NoCodec : ImageCodec {
        override fun decode(bytes: ByteArray): Raster = error("no crop expected")
        override fun encodeJpeg(opaque: Raster): PreparedImage = error("no crop expected")
    }

    /** "Decodes" to a 4x2 raster whose pixels are their own index, and "encodes" a raster as those indices. */
    private object IndexCodec : ImageCodec {
        override fun decode(bytes: ByteArray): Raster = Raster(4, 2, IntArray(8) { it })
        override fun encodeJpeg(opaque: Raster): PreparedImage =
            PreparedImage(ByteArray(opaque.argb.size) { opaque.argb[it].toByte() }, opaque.width, opaque.height)
    }

    private fun vsrid(url: String): LensOverlayRequestId =
        LensOverlayRequestId.ADAPTER.decode(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(query(url).getValue("vsrid")))

    @Test
    fun opensASessionThenUploadsUnderItsServerId() = runTest {
        val lens = FakeLens(routing)
        val result = lens.session().scan(image, ScanOptions(targetLanguage = "ru"))

        lens.urls shouldContainExactly listOf(CLUSTER, "$CRUPLOAD?gsessionid=srv-1")
        lens.clusterRequests.single() shouldBe LensOverlayServerClusterInfoRequest(
            enable_search_session_id = true,
            surface = Surface.SURFACE_CHROMIUM,
            platform = Platform.PLATFORM_WEB,
            rendering_context = RenderingContext(
                rendering_environment = LensRenderingEnvironment.RENDERING_ENV_LENS_OVERLAY,
            ),
        )
        // The session's scan is the same request as the stateless one, translation and all.
        val objects = lens.uploads.single().objects_request!!
        objects.image_data!!.payload!!.image_bytes shouldBe image.jpeg.toByteString()
        objects.request_context!!.client_context!!.client_filters!!.filter.single().translate!!.target_language shouldBe "ru"
        result.objects shouldContainExactly listOf(DetectedObject("obj-1", Box(0.5f, 0.5f, 0.2f, 0.4f, 0f)))
    }

    @Test
    fun requestIdsCountUpAcrossUploadInteractionAndUrl() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session(seed = 7)
        session.scan(image)
        val first = vsrid(session.searchImageUrl())
        val second = vsrid(session.searchObjectUrl("obj-1"))

        // What the session draws, in order, from the same seed.
        val random = Random(7)
        val uuid = random.nextLong() ushr 1
        val contextId = random.nextLong() ushr 1
        val analytics = List(3) { random.nextBytes(16).toByteString() }

        val (upload, region, tap) = lens.ids()
        (lens.ids() + first + second).map { it.uuid }.distinct() shouldContainExactly listOf(uuid)
        (lens.ids() + first + second).map { it.context_id }.distinct() shouldContainExactly listOf(contextId)
        (lens.ids() + first + second).map { it.image_sequence_id }.distinct() shouldContainExactly listOf(1)
        (lens.ids() + first + second).map { it.long_context_id }.distinct() shouldContainExactly listOf(1)
        (lens.ids() + first + second).map { it.routing_info }.distinct() shouldContainExactly listOf(routing)
        (lens.ids() + first + second).map { it.time_usec }.distinct() shouldContainExactly listOf(1_000_000_000L)

        // Upload 1; interaction 2 and its URL 3; the next interaction 4 and its URL 5.
        listOf(upload, region, first, tap, second).map { it.sequence_id } shouldContainExactly listOf(1, 2, 3, 4, 5)
        // A URL shares its interaction's analytics id; every request makes a new one.
        listOf(upload, region, first, tap, second).map { it.analytics_id } shouldContainExactly
            listOf(analytics[0], analytics[1], analytics[1], analytics[2], analytics[2])

        lens.urls.drop(1).distinct() shouldContainExactly listOf("$CRUPLOAD?gsessionid=srv-1")
    }

    @Test
    fun wholeImageSearchSendsTheUploadItselfAsTheCrop() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session()
        session.scan(image, ScanOptions(targetLanguage = "ru"))
        session.searchImageUrl()

        val interaction = lens.uploads[1].interaction_request!!
        val metadata = interaction.interaction_request_metadata!!
        metadata.type shouldBe LensOverlayInteractionRequestMetadata.Type.REGION_SEARCH
        metadata.selection_metadata!!.region!!.region shouldBe
            CenterRotatedBox(center_x = 0.5f, center_y = 0.5f, width = 1f, height = 1f, coordinate_type = CoordinateType.NORMALIZED)
        metadata.query_metadata shouldBe null
        interaction.image_crop!!.image!!.image_content shouldBe image.jpeg.toByteString()
        val zoomed = interaction.image_crop.zoomed_crop!!
        (zoomed.parent_width to zoomed.parent_height) shouldBe (4 to 2)
        zoomed.zoom shouldBe 1f
        zoomed.crop shouldBe metadata.selection_metadata.region.region
        // An interaction states a selection, never a translation.
        interaction.request_context!!.client_context!!.client_filters!!.filter.single().filter_type shouldBe
            LensOverlayFilterType.AUTO_FILTER
    }

    @Test
    fun regionSearchSendsTheCroppedPixelsAndReturnsTheirText() = runTest {
        val reread = LensOverlayServerResponse(
            interaction_response = LensOverlayInteractionResponse(
                text = Text(
                    text_layout = TextLayout(
                        paragraphs = listOf(
                            TextLayout.Paragraph(lines = listOf(TextLayout.Line(words = listOf(TextLayout.Word(plain_text = "fine print"))))),
                        ),
                    ),
                ),
            ),
        )
        val lens = FakeLens(routing, interactionReply = reread)
        val session = lens.session(codec = IndexCodec)
        session.scan(image)
        // The right half, overshooting the edge: columns 2 and 3 of both rows.
        val found = session.searchRegion(Region(0.5f, 0f, 0.9f, 1f), text = " red one ")

        val interaction = lens.uploads[1].interaction_request!!
        interaction.image_crop!!.image!!.image_content shouldBe byteArrayOf(2, 3, 6, 7).toByteString()
        val box = interaction.interaction_request_metadata!!.selection_metadata!!.region!!.region!!
        listOf(box.center_x, box.center_y, box.width, box.height) shouldContainExactly listOf(0.75f, 0.5f, 0.5f, 1f)
        interaction.image_crop.zoomed_crop!!.crop shouldBe box
        (interaction.image_crop.zoomed_crop.parent_width to interaction.image_crop.zoomed_crop.parent_height) shouldBe (4 to 2)
        interaction.interaction_request_metadata.query_metadata!!.text_query!!.query shouldBe "red one"

        found.text!!.text shouldBe "fine print"
        // Placed in the uploaded image, not in the crop.
        (found.text.imageWidth to found.text.imageHeight) shouldBe (4 to 2)
        query(found.url)["udm"] shouldBe "24"
    }

    @Test
    fun tappingAnObjectSendsItsIdAndNoPixels() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session()
        val scan = session.scan(image)
        session.searchObjectUrl(scan.objects.single().id)

        val interaction = lens.uploads[1].interaction_request!!
        interaction.interaction_request_metadata!!.type shouldBe LensOverlayInteractionRequestMetadata.Type.TAP
        interaction.interaction_request_metadata.selection_metadata!!.object_!!.object_id shouldBe "obj-1"
        interaction.interaction_request_metadata.selection_metadata.region shouldBe null
        interaction.image_crop shouldBe null
    }

    @Test
    fun urlParametersForEveryKindOfSearch() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session(page = SearchPageOptions(uiLanguage = "ru", darkMode = true), codec = IndexCodec)
        session.scan(image)

        val visual = query(session.searchImageUrl())
        visual.keys.toList() shouldContainExactly
            listOf("q", "lns_mode", "lns_fp", "lns_surface", "gsessionid", "udm", "vsrid", "source", "hl", "cs")
        visual - "vsrid" shouldBe mapOf(
            "q" to "", "lns_mode" to "un", "lns_fp" to "1", "lns_surface" to "42",
            // The search session id: the server one never leaves crupload.
            "gsessionid" to "sch-1",
            "udm" to "26", "source" to "chrome.cr.ctxi", "hl" to "ru", "cs" to "1",
        )

        val multimodal = query(session.searchRegionUrl(Region(0f, 0f, 0.5f, 1f), "in red"))
        listOf(multimodal["q"], multimodal["lns_mode"], multimodal["udm"]) shouldContainExactly listOf("in%20red", "mu", "24")

        val tapped = query(session.searchObjectUrl("obj-1"))
        listOf(tapped["q"], tapped["lns_mode"], tapped["udm"]) shouldContainExactly listOf("", "un", "26")

        val ai = session.askAiUrl("what manga is this?")
        ai.startsWith("https://www.google.com/search?q=what%20manga%20is%20this%3F&lns_mode=mu&") shouldBe true
        listOf(query(ai)["udm"], query(ai)["gsessionid"]) shouldContainExactly listOf("50", "sch-1")
        // The question travels in the interaction as well as on the URL.
        lens.uploads.last().interaction_request!!.interaction_request_metadata!!.query_metadata!!.text_query!!.query shouldBe
            "what manga is this?"

        val before = lens.urls.size
        session.searchTextUrl(" 進撃の巨人 ") shouldBe "https://www.google.com/search" +
            "?q=%E9%80%B2%E6%92%83%E3%81%AE%E5%B7%A8%E4%BA%BA&lns_fp=1&lns_mode=text&lns_surface=42&hl=ru&cs=1"
        // Words need no upload.
        lens.urls.size shouldBe before
    }

    @Test
    fun aiModeCanBeAskedAboutARegion() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session(codec = IndexCodec)
        session.scan(image)
        query(session.askAiUrl("who?", Region(0f, 0f, 0.5f, 0.5f)))["udm"] shouldBe "50"
        lens.uploads[1].interaction_request!!.image_crop!!.image!!.image_content shouldBe byteArrayOf(0, 1).toByteString()
        shouldThrow<IllegalArgumentException> { session.askAiUrl("  ") }
    }

    @Test
    fun anExpiredSessionIsReopenedAndTheImageUploadedAgain() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session()
        session.scan(image, ScanOptions(ocrLanguage = "ja"))

        // 28 minutes in: still the same session.
        lens.now += 28 * 60_000
        val early = session.searchImageUrl()
        query(early)["gsessionid"] shouldBe "sch-1"
        lens.urls.size shouldBe 3

        // 29 minutes after opening is inside the one-minute margin before the 30-minute lifetime.
        lens.now += 60_000
        val late = session.searchImageUrl()
        lens.urls.drop(3) shouldContainExactly listOf(CLUSTER, "$CRUPLOAD?gsessionid=srv-2", "$CRUPLOAD?gsessionid=srv-2")
        query(late)["gsessionid"] shouldBe "sch-2"

        // A new session is a new uuid, counted from one again, with the same image and options.
        val reupload = lens.uploads[2]
        reupload.objects_request!!.image_data!!.payload!!.image_bytes shouldBe image.jpeg.toByteString()
        reupload.objects_request.request_context!!.client_context!!.locale_context!!.language shouldBe "ja"
        val ids = lens.ids()
        ids[2].uuid shouldNotBe ids[0].uuid
        listOf(ids[2], ids[3], vsrid(late)).map { it.sequence_id } shouldContainExactly listOf(1, 2, 3)
        listOf(ids[3], vsrid(late)).map { it.uuid }.distinct() shouldContainExactly listOf(ids[2].uuid)

        // And it is fresh again for the next half hour.
        lens.now += 28 * 60_000
        session.searchImageUrl()
        lens.urls.size shouldBe 7
    }

    @Test
    fun aSessionWithoutIdsIsRefusedAndNothingIsKept() = runTest {
        val lens = FakeLens(routing, clusterReply = { n ->
            if (n == 1) LensOverlayServerClusterInfoResponse(server_session_id = "srv", search_session_id = "") else null
        })
        val session = lens.session()
        shouldThrow<LensException.MalformedResponse> { session.scan(image) }
        // No upload was made into the half-open session, and there is nothing to search.
        lens.uploads.size shouldBe 0
        shouldThrow<IllegalStateException> { session.searchImageUrl() }
        // The next scan starts clean.
        session.scan(image)
        query(session.searchImageUrl())["gsessionid"] shouldBe "sch-2"
    }

    @Test
    fun routingInfoIsOptional() = runTest {
        val lens = FakeLens(routing, clusterReply = { LensOverlayServerClusterInfoResponse(server_session_id = "a b", search_session_id = "s") })
        val session = lens.session()
        session.scan(image)
        vsrid(session.searchImageUrl()).routing_info shouldBe null
        // The server session id is a query value like any other.
        lens.urls[1] shouldBe "$CRUPLOAD?gsessionid=a%20b"
    }

    @Test
    fun aServerErrorOnTheInteractionIsNotAUrl() = runTest {
        val refused = LensOverlayServerResponse(
            error = LensOverlayServerError(error_type = LensOverlayServerError.ErrorType.MISSING_REQUEST),
        )
        val session = FakeLens(routing, interactionReply = refused).session()
        session.scan(image)
        shouldThrow<LensException.ServerError> { session.searchImageUrl() }
    }

    @Test
    fun searchingBeforeScanningIsACallerError() = runTest {
        val lens = FakeLens(routing)
        shouldThrow<IllegalStateException> { lens.session().searchImageUrl() }
        lens.urls.size shouldBe 0
    }

    @Test
    fun aRegionOutsideTheImageIsRefusedBeforeAnyRequest() = runTest {
        val lens = FakeLens(routing)
        val session = lens.session()
        session.scan(image)
        shouldThrow<IllegalArgumentException> { session.searchRegionUrl(Region(1.2f, 0f, 0.5f, 0.5f)) }
        lens.urls.size shouldBe 2
    }

    private companion object {
        const val CRUPLOAD = "https://lens.test/v1/crupload"
        const val CLUSTER = "https://lens.test/v1/gsessionid"

        fun box(cx: Float, cy: Float, w: Float, h: Float) = Geometry(
            bounding_box = CenterRotatedBox(
                center_x = cx, center_y = cy, width = w, height = h, coordinate_type = CoordinateType.NORMALIZED,
            ),
        )

        /** The query parameters of [url], still percent-encoded, in order. */
        fun query(url: String): Map<String, String> =
            url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}

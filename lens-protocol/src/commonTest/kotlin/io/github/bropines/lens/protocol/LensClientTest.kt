package io.github.bropines.lens.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import lens.LensOverlayObjectsResponse
import lens.LensOverlayServerRequest
import lens.LensOverlayServerResponse
import lens.Text
import lens.TextLayout
import kotlin.test.Test

class LensClientTest {
    private val image = PreparedImage(byteArrayOf(1, 2, 3), 10, 10)

    @Test
    fun sendsChromiumsHeadersAndParsesTheReply() = runTest {
        val reply = LensOverlayServerResponse(
            objects_response = LensOverlayObjectsResponse(
                text = Text(
                    text_layout = TextLayout(
                        paragraphs = listOf(
                            TextLayout.Paragraph(
                                lines = listOf(TextLayout.Line(words = listOf(TextLayout.Word(plain_text = "hi")))),
                            ),
                        ),
                    ),
                ),
            ),
        ).encode()
        var seenKey: String? = null
        var seenType: String? = null
        val engine = MockEngine { request ->
            seenKey = request.headers["X-Goog-Api-Key"]
            seenType = request.body.contentType?.toString() ?: request.headers["Content-Type"]
            LensOverlayServerRequest.ADAPTER.decode(request.body.toByteArray()).objects_request!!.image_data!!
                .image_metadata!!.width shouldBe 10
            respond(reply, HttpStatusCode.OK)
        }
        val result = LensClient(HttpClient(engine), apiKey = "k").scan(image)
        result.text shouldBe "hi"
        seenKey shouldBe "k"
        seenType shouldBe "application/x-protobuf"
    }

    @Test
    fun aNonSuccessStatusIsAnHttpError() = runTest {
        val engine = MockEngine { respond("quota", HttpStatusCode.TooManyRequests) }
        val error = shouldThrow<LensException.Http> { LensClient(HttpClient(engine)).scan(image) }
        error.status shouldBe 429
    }
}

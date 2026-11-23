package io.github.bropines.lens.protocol

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import lens.LensOverlayFilterType
import lens.LensOverlayServerRequest
import lens.LocaleContext
import lens.TextLayout
import lens.TranslationData
import okio.Buffer
import kotlin.test.Test

/**
 * The protocol facts that the editions downgrade has to get right, checked on
 * the bytes rather than on the generated Kotlin.
 */
class WireFormatTest {
    private val image = PreparedImage(byteArrayOf(1, 2, 3), 640, 480)
    private val identity = RequestIdentity(uuid = 42, timeMicros = 1_700_000_000_000_000)

    @Test
    fun implicitPresenceLeavesAnEmptyStringOffTheWire() {
        // LocaleContext is in an IMPLICIT file: language "" must not be sent.
        // Mapping it to proto2 would send `language: ""`, a different request.
        fieldNumbers(LocaleContext(language = "", region = "US", time_zone = "UTC").encode()) shouldContainExactly listOf(2, 3)
    }

    @Test
    fun explicitOverrideInsideAnImplicitFileKeepsAnEmptySeparator() {
        // text_separator overrides presence to EXPLICIT: empty and absent differ.
        fieldNumbers(TextLayout.Word(plain_text = "a", text_separator = "").encode()) shouldContainExactly listOf(2, 3)
        fieldNumbers(TextLayout.Word(plain_text = "a").encode()) shouldContainExactly listOf(2)
    }

    @Test
    fun explicitFileSendsASetZero() {
        // TranslationData is EXPLICIT (edition 2023 default): a set 0 is on the wire.
        fieldNumbers(TranslationData.Line(start = 0, end = 0).encode()) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun plainScanSendsOnlyAutoFilter() {
        val request = LensOverlayServerRequest.ADAPTER.decode(LensRequests.encode(image, ScanOptions(), identity))
        val context = request.objects_request!!.request_context!!.client_context!!
        context.client_filters!!.filter.map { it.filter_type } shouldContainExactly listOf(LensOverlayFilterType.AUTO_FILTER)
        context.locale_context!!.language shouldBe ""
        request.objects_request.image_data!!.image_metadata!!.width shouldBe 640
    }

    @Test
    fun translateReplacesAutoFilter() {
        val bytes = LensRequests.encode(image, ScanOptions(targetLanguage = "ru"), identity)
        val filters = LensOverlayServerRequest.ADAPTER.decode(bytes)
            .objects_request!!.request_context!!.client_context!!.client_filters!!.filter
        filters.map { it.filter_type } shouldContainExactly listOf(LensOverlayFilterType.TRANSLATE)
        filters.single().translate!!.target_language shouldBe "ru"
        filters.single().translate!!.source_language shouldBe null
    }

    @Test
    fun encodingIsDeterministicForAFixedIdentity() {
        val a = LensRequests.encode(image, ScanOptions(targetLanguage = "ja"), identity)
        val b = LensRequests.encode(image, ScanOptions(targetLanguage = "ja"), identity)
        a.contentEquals(b) shouldBe true
    }

    /** The top-level field numbers of an encoded message, in wire order. */
    private fun fieldNumbers(bytes: ByteArray): List<Int> {
        val reader = com.squareup.wire.ProtoReader(Buffer().write(bytes))
        val fields = mutableListOf<Int>()
        val token = reader.beginMessage()
        while (true) {
            val tag = reader.nextTag()
            if (tag == -1) break
            fields += tag
            reader.skip()
        }
        reader.endMessageAndGetUnknownFields(token)
        return fields
    }
}

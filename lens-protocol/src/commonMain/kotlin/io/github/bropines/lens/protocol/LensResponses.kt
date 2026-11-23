package io.github.bropines.lens.protocol

import lens.CoordinateType
import lens.Geometry
import lens.LensOverlayServerError
import lens.LensOverlayServerResponse
import lens.TextLayout
import lens.TranslationData

/** Turns a `crupload` reply into a [ScanResult]. */
public object LensResponses {
    /**
     * Decodes a raw, *decompressed* response body. [imageWidth]/[imageHeight]
     * are the uploaded image's size: boxes of `coordinate_type` IMAGE come in
     * its pixels and are normalised here, so every box handed out follows one
     * rule.
     */
    public fun parse(body: ByteArray, imageWidth: Int, imageHeight: Int): ScanResult {
        val response = try {
            LensOverlayServerResponse.ADAPTER.decode(body)
        } catch (e: Exception) {
            throw LensException.MalformedResponse("not a LensOverlayServerResponse", e)
        }
        return parse(response, imageWidth, imageHeight)
    }

    public fun parse(response: LensOverlayServerResponse, imageWidth: Int, imageHeight: Int): ScanResult {
        // Only a non-zero error_type is a failure: an `error` saying
        // UNKNOWN_TYPE(0) can sit beside a perfectly good objects_response.
        val error = response.error
        if (error != null && error.error_type != LensOverlayServerError.ErrorType.UNKNOWN_TYPE) {
            throw LensException.ServerError(error.error_type.name)
        }
        val objects = response.objects_response
            ?: throw LensException.MalformedResponse("neither an objects response nor an error")

        val scale = Scale(imageWidth.toFloat(), imageHeight.toFloat())
        val layout = objects.text?.text_layout
        val translations = objects.deep_gleams.map { gleam -> gleam.translation?.takeIf { it.succeeded() } }

        // By index, not by position among the successes: a gleam's place in
        // deep_gleams is its paragraph's index, so a failure leaves a hole.
        // Extra gleams (translating something not parsed) are ignored.
        val paragraphs = layout?.paragraphs.orEmpty().mapIndexed { index, paragraph ->
            parseParagraph(paragraph, scale, translations.getOrNull(index))
        }
        return ScanResult(
            paragraphs = paragraphs,
            language = objects.text?.content_language?.ifEmpty { null },
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    /** Anything but SUCCESS with text is dropped: an echoed or empty string is worse than none. */
    private fun TranslationData.succeeded(): Boolean =
        status?.code == TranslationData.Status.Code.SUCCESS && !translation.isNullOrEmpty()

    private class Scale(val width: Float, val height: Float)

    private fun boxOf(geometry: Geometry?, scale: Scale): Box? {
        val box = geometry?.bounding_box ?: return null
        val image = box.coordinate_type == CoordinateType.IMAGE && scale.width > 0 && scale.height > 0
        val sx = if (image) 1 / scale.width else 1f
        val sy = if (image) 1 / scale.height else 1f
        return Box(
            centerX = (box.center_x ?: 0f) * sx,
            centerY = (box.center_y ?: 0f) * sy,
            width = (box.width ?: 0f) * sx,
            height = (box.height ?: 0f) * sy,
            rotation = box.rotation_z ?: 0f,
        )
    }

    private fun parseParagraph(paragraph: TextLayout.Paragraph, scale: Scale, data: TranslationData?): Paragraph {
        val lines = paragraph.lines.map { line ->
            val words = line.words.map { word ->
                Word(
                    text = word.plain_text,
                    separator = word.text_separator,
                    box = boxOf(word.geometry, scale),
                    latex = word.formula_metadata?.latex?.ifEmpty { null },
                )
            }
            Line(
                text = words.joinToString("") { it.text + it.separator.orEmpty() }.trimEnd(),
                words = words,
                box = boxOf(line.geometry, scale),
                translation = null,
                translationStyle = null,
            )
        }
        val base = Paragraph(
            text = lines.joinToString("\n") { it.text },
            lines = lines,
            box = boxOf(paragraph.geometry, scale),
            language = paragraph.content_language.ifEmpty { null },
            writingDirection = paragraph.writing_direction.toModel(),
            translation = null,
        )
        return if (data == null) base else attachTranslation(base, data)
    }

    private fun attachTranslation(paragraph: Paragraph, data: TranslationData): Paragraph {
        val text = data.translation ?: return paragraph
        // Lens sends one entry per *source* line, in order; a shorter list
        // leaves the remaining lines untranslated rather than being an error.
        val lines = paragraph.lines.mapIndexed { index, line ->
            val ranges = data.line.getOrNull(index) ?: return@mapIndexed line
            line.copy(
                translation = lineTranslation(text, ranges, data.line.getOrNull(index + 1)),
                translationStyle = styleOf(ranges),
            )
        }
        return paragraph.copy(
            lines = lines,
            translation = ParagraphTranslation(
                text = text,
                sourceLanguage = data.source_language?.ifEmpty { null },
                targetLanguage = data.target_language?.ifEmpty { null },
                writingDirection = data.writing_direction.toModel(),
                alignment = when (data.alignment) {
                    lens.Alignment.RIGHT_ALIGNED -> TextAlignment.End
                    lens.Alignment.CENTER_ALIGNED -> TextAlignment.Center
                    else -> TextAlignment.Start
                },
                justified = data.justified ?: false,
            ),
        )
    }

    /**
     * One line of a translation, built the way Chromium's
     * `CreateTranslatedLineMojomFromProto` builds it: each word's range plus
     * the gap up to the next word, and for the last word the gap up to the
     * next line's first word. That keeps the pieces concatenating back into the
     * paragraph. Falls back to the line's own range when it has no word ranges.
     *
     * Ranges are UTF-16 code units, which is what a Kotlin String indexes, so
     * no re-encoding is needed (the proto's "Unicode characters" is wrong).
     */
    internal fun lineTranslation(text: String, line: TranslationData.Line, next: TranslationData.Line?): String? {
        if (line.word.isEmpty()) return slice(text, line.start, line.end)?.takeIf { it.isNotBlank() }
        val out = StringBuilder()
        line.word.forEachIndexed { index, word ->
            slice(text, word.start, word.end)?.let(out::append)
            val gapEnd = line.word.getOrNull(index + 1)?.start ?: next?.word?.firstOrNull()?.start
            slice(text, word.end, gapEnd)?.let(out::append)
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** Null when the range is unset or impossible; an empty slice is a real answer. */
    private fun slice(text: String, start: Int?, end: Int?): String? {
        if (start == null || end == null || start < 0 || start > end || end > text.length) return null
        return text.substring(start, end)
    }

    private fun styleOf(line: TranslationData.Line): LineStyle? {
        val style = line.style
        val patch = line.background_image_data?.let { data ->
            val image = data.background_image?.toByteArray()?.takeIf { it.isNotEmpty() } ?: return@let null
            BackgroundPatch(
                image = image,
                format = when (data.file_format) {
                    TranslationData.BackgroundImageData.FileFormat.RAW_BYTES_RGBA -> PatchFormat.RawRgba
                    TranslationData.BackgroundImageData.FileFormat.PNG_RGBA -> PatchFormat.Png
                    TranslationData.BackgroundImageData.FileFormat.WEBP_RGBA -> PatchFormat.Webp
                    TranslationData.BackgroundImageData.FileFormat.JPEG_RGB_PNG_MASK -> PatchFormat.JpegWithPngMask
                    else -> PatchFormat.Unknown
                },
                width = data.image_width ?: 0,
                height = data.image_height ?: 0,
                verticalPadding = data.vertical_padding ?: 0f,
                horizontalPadding = data.horizontal_padding ?: 0f,
                textMask = data.text_mask?.toByteArray()?.takeIf { it.isNotEmpty() },
            )
        }
        if (style == null && patch == null) return null
        return LineStyle(style?.text_color, style?.background_primary_color, patch)
    }

    private fun lens.WritingDirection?.toModel(): WritingDirection = when (this) {
        lens.WritingDirection.WRITING_DIRECTION_RIGHT_TO_LEFT -> WritingDirection.RightToLeft
        lens.WritingDirection.WRITING_DIRECTION_TOP_TO_BOTTOM -> WritingDirection.TopToBottom
        else -> WritingDirection.LeftToRight
    }
}

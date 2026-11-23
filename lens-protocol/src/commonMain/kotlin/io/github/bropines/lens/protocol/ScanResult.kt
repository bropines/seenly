package io.github.bropines.lens.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * What Lens found in one image.
 *
 * Every box is normalised to the image: `centerX`/`width` are fractions of its
 * width, `centerY`/`height` of its height. Multiply by whichever copy you draw
 * on, original or uploaded: a downscale keeps the aspect ratio, so both give
 * the right answer.
 */
@Serializable
public data class ScanResult(
    val paragraphs: List<Paragraph>,
    /** The language Lens detected for the whole image, when it said. */
    val language: String?,
    /** The size of the image as uploaded. */
    val imageWidth: Int,
    val imageHeight: Int,
    /**
     * The things Lens singled out in the image: what Chrome draws as tappable
     * dots. Each can be searched with [LensSearchSession.searchObjectUrl].
     */
    val objects: List<DetectedObject> = emptyList(),
) {
    /** Every paragraph's text, joined with newlines. */
    val text: String get() = paragraphs.joinToString("\n") { it.text }.trim()

    /** Every successful translation, joined with newlines, or null if there was none. */
    val translation: String?
        get() = paragraphs.mapNotNull { it.translation?.text }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Something Lens detected in the image. [id] means something only to the upload it came from. */
@Serializable
public data class DetectedObject(val id: String, val box: Box)

@Serializable
public data class Paragraph(
    val text: String,
    val lines: List<Line>,
    val box: Box?,
    val language: String?,
    val writingDirection: WritingDirection,
    /**
     * This paragraph's translation, when one was asked for and succeeded.
     * Lens pairs translations with paragraphs by position, so one it could not
     * translate leaves a null here instead of shifting the rest.
     */
    val translation: ParagraphTranslation?,
)

@Serializable
public data class Line(
    val text: String,
    val words: List<Word>,
    val box: Box?,
    /**
     * This line's share of the paragraph's translation, assembled from the
     * word ranges the way Chromium's `CreateTranslatedLineMojomFromProto` does.
     *
     * The separator after the last word is kept, so it can **end in a space**:
     * that is what makes the lines concatenate back into the paragraph. Trim
     * it when measuring or centring a single line.
     */
    val translation: String?,
    /** How Lens suggests drawing [translation]: colours and an erased background. */
    val translationStyle: LineStyle?,
)

@Serializable
public data class Word(
    val text: String,
    /**
     * What separates this word from the next: usually a space, empty at a line
     * end or in scripts without word spacing, and null when Lens sent none.
     */
    val separator: String?,
    val box: Box?,
    /** For a word Lens recognised as a formula, its LaTeX. */
    val latex: String? = null,
)

/** A rotated box, normalised to the image (see [ScanResult]). */
@Serializable
public data class Box(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    /** Clockwise rotation in radians. */
    val rotation: Float,
) {
    val left: Float get() = centerX - width / 2
    val top: Float get() = centerY - height / 2
    val right: Float get() = centerX + width / 2
    val bottom: Float get() = centerY + height / 2
}

@Serializable
public data class ParagraphTranslation(
    val text: String,
    /** The language Lens translated from, useful when it was asked to detect. */
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val writingDirection: WritingDirection,
    val alignment: TextAlignment,
    val justified: Boolean,
)

@Serializable
public data class LineStyle(
    /** ARGB as Lens sends it, or null if it did not say. */
    val textColor: Int?,
    val backgroundColor: Int?,
    /** The source text erased: a patch to paint over the line before drawing the translation. */
    val background: BackgroundPatch?,
)

/**
 * The line's background with the original text inpainted away, as Chromium's
 * overlay uses it. It covers the line's box grown by the paddings, which are
 * fractions of the box.
 */
@Serializable
public data class BackgroundPatch(
    @Serializable(with = Base64Serializer::class) val image: ByteArray,
    val format: PatchFormat,
    val width: Int,
    val height: Int,
    val verticalPadding: Float,
    val horizontalPadding: Float,
    /** For [PatchFormat.JpegWithPngMask]: the PNG alpha mask. */
    @Serializable(with = Base64Serializer::class) val textMask: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is BackgroundPatch &&
        image.contentEquals(other.image) && format == other.format && width == other.width &&
        height == other.height && verticalPadding == other.verticalPadding &&
        horizontalPadding == other.horizontalPadding && textMask.contentEquals(other.textMask)

    override fun hashCode(): Int = image.contentHashCode() * 31 + format.hashCode()
}

@Serializable
public enum class PatchFormat { Unknown, RawRgba, Png, Webp, JpegWithPngMask }

@Serializable
public enum class WritingDirection { LeftToRight, RightToLeft, TopToBottom }

@Serializable
public enum class TextAlignment { Start, End, Center }

internal object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("io.github.bropines.lens.Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.encode(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

package io.github.bropines.lens.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import io.github.bropines.lens.protocol.BackgroundPatch
import io.github.bropines.lens.protocol.PatchFormat
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A [ScanResult] made ready to draw: every line's [BackgroundPatch] decoded
 * once into an [ImageBitmap], plus the fonts text is set in.
 *
 * Make one with [rememberOverlayState] in a composition or [decodeOverlay]
 * anywhere else, and share it between [TranslatedImage] and
 * [renderTranslatedBitmap]. It is immutable and safe to use from any thread.
 */
@Stable
public class OverlayState internal constructor(
    /** The result this was decoded from. */
    public val result: ScanResult,
    private val patches: Map<Long, ImageBitmap>,
    internal val fontFamilyResolver: FontFamily.Resolver,
) {
    /** How many background patches decoded successfully. */
    public val patchCount: Int get() = patches.size

    /** The decoded background of line [line] of paragraph [paragraph], if it had a usable one. */
    internal fun patch(paragraph: Int, line: Int): ImageBitmap? = patches[patchKey(paragraph, line)]
}

private fun patchKey(paragraph: Int, line: Int): Long = (paragraph.toLong() shl 32) or (line.toLong() and 0xFFFFFFFFL)

/**
 * Decodes every background patch of [result] on [dispatcher].
 *
 * A patch that does not decode is skipped; the renderer falls back to a flat
 * fill for that line. Cancellation is honoured between patches.
 *
 * @param fontFamilyResolver where translated text gets its fonts: in a
 *   composition `LocalFontFamilyResolver.current`, elsewhere the platform's
 *   `createFontFamilyResolver(...)`.
 */
public suspend fun decodeOverlay(
    result: ScanResult,
    fontFamilyResolver: FontFamily.Resolver,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): OverlayState = withContext(dispatcher) {
    val patches = HashMap<Long, ImageBitmap>()
    result.paragraphs.forEachIndexed { p, paragraph ->
        paragraph.lines.forEachIndexed { l, line ->
            val patch = line.translationStyle?.background ?: return@forEachIndexed
            ensureActive()
            decodePatch(patch)?.let { patches[patchKey(p, l)] = it }
        }
    }
    OverlayState(result, patches, fontFamilyResolver)
}

/**
 * Decodes [result] off the main thread and remembers it. Returns null while
 * there is no result or it is still decoding, and never a state that belongs
 * to a previous result - unless [keepPrevious] is set: then a replaced result
 * (the same page in a new language, say) keeps showing until its successor
 * has decoded, instead of blinking to the bare image in between.
 */
@Composable
public fun rememberOverlayState(result: ScanResult?, keepPrevious: Boolean = false): OverlayState? {
    val resolver = LocalFontFamilyResolver.current
    var state by remember(resolver) { mutableStateOf<OverlayState?>(null) }
    LaunchedEffect(result, resolver) {
        if (result == null || !keepPrevious) state = null
        if (result != null) state = decodeOverlay(result, resolver)
    }
    val current = state
    return if (keepPrevious || current?.result === result) current else null
}

/** One patch as a bitmap with the right alpha, or null when it cannot be decoded. */
internal fun decodePatch(patch: BackgroundPatch): ImageBitmap? = try {
    when (patch.format) {
        PatchFormat.RawRgba ->
            rgbaBytesToArgb(patch.image, patch.width, patch.height)
                ?.let { imageBitmapFromArgb(it, patch.width, patch.height) }

        PatchFormat.JpegWithPngMask -> {
            val colour = decodeEncodedImage(patch.image)
            val mask = patch.textMask?.let(::decodeEncodedImage)
            if (colour == null || mask == null) {
                colour
            } else {
                val merged = mergeAlphaMask(
                    colour.argbPixels(), colour.width, colour.height,
                    mask.argbPixels(), mask.width, mask.height,
                )
                imageBitmapFromArgb(merged, colour.width, colour.height)
            }
        }

        // Unknown is tried too: the decoders sniff the container themselves.
        PatchFormat.Png, PatchFormat.Webp, PatchFormat.Unknown -> decodeEncodedImage(patch.image)
    }
} catch (e: Exception) {
    null
}

/** All pixels as non-premultiplied ARGB, row by row. */
internal fun ImageBitmap.argbPixels(): IntArray = IntArray(width * height).also { readPixels(it) }

/** Decodes a PNG, WebP or JPEG, or returns null when the bytes are none of those. */
internal expect fun decodeEncodedImage(bytes: ByteArray): ImageBitmap?

/** Makes a bitmap from non-premultiplied ARGB pixels. May modify [pixels]. */
internal expect fun imageBitmapFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap

/**
 * This bitmap in a form a software canvas can draw and read. Android's
 * hardware bitmaps cannot be; they are copied. Everything else is returned as is.
 */
internal expect fun ImageBitmap.forSoftwareCanvas(): ImageBitmap

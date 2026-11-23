package io.github.bropines.lens.overlay

import kotlin.math.pow

// Pure pixel arithmetic, kept free of any graphics type so it can be tested on
// every target. A pixel is a non-premultiplied ARGB Int, as Lens sends colours
// and as `ImageBitmap.readPixels` returns them.

internal const val ARGB_BLACK: Int = 0xFF000000.toInt()
internal const val ARGB_WHITE: Int = 0xFFFFFFFF.toInt()

private fun channelToLinear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an ARGB colour, ignoring alpha: 0 is black, 1 is white. */
internal fun relativeLuminance(argb: Int): Double =
    0.2126 * channelToLinear((argb shr 16) and 0xFF) +
        0.7152 * channelToLinear((argb shr 8) and 0xFF) +
        0.0722 * channelToLinear(argb and 0xFF)

/** WCAG contrast ratio between two colours, from 1 (none) to 21 (black on white). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Black or white, whichever reads better on [background]. */
internal fun contrastingTextColor(background: Int): Int =
    if (contrastRatio(ARGB_BLACK, background) >= contrastRatio(ARGB_WHITE, background)) ARGB_BLACK else ARGB_WHITE

/**
 * The text colour for a line: the one Lens sent, or black/white by contrast
 * with the background when it sent none (or sent a fully transparent one).
 */
internal fun resolveTextColor(textColor: Int?, background: Int): Int =
    textColor?.takeIf { (it ushr 24) != 0 } ?: contrastingTextColor(background)

/**
 * The per-channel median of [pixels], opaque. Used to sample a background
 * colour from around a line: a median ignores the few samples that land on a
 * bubble's border or on neighbouring text, where a mean would grey them in.
 */
internal fun medianColor(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    fun median(shift: Int): Int {
        val values = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
        values.sort()
        return values[values.size / 2]
    }
    return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
}

/**
 * Turns `width * height * 4` bytes of R, G, B, A into ARGB pixels, or null when
 * [bytes] is too short for the size.
 */
internal fun rgbaBytesToArgb(bytes: ByteArray, width: Int, height: Int): IntArray? {
    val count = width.toLong() * height
    if (width <= 0 || height <= 0 || count * 4 > bytes.size) return null
    return IntArray(count.toInt()) { i ->
        val o = i * 4
        val r = bytes[o].toInt() and 0xFF
        val g = bytes[o + 1].toInt() and 0xFF
        val b = bytes[o + 2].toInt() and 0xFF
        val a = bytes[o + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Combines the two halves of a `JpegWithPngMask` patch: colour from the JPEG's
 * pixels, alpha from the PNG mask's.
 *
 * The mask is read from its alpha channel when it has one. A mask that decodes
 * fully opaque is a greyscale image instead, and then its brightness is the
 * alpha (white is opaque). A mask of another size is stretched over the colour
 * image, nearest neighbour.
 *
 * @return [rgbWidth] x [rgbHeight] non-premultiplied ARGB pixels.
 */
internal fun mergeAlphaMask(
    rgb: IntArray,
    rgbWidth: Int,
    rgbHeight: Int,
    mask: IntArray,
    maskWidth: Int,
    maskHeight: Int,
): IntArray {
    require(rgb.size >= rgbWidth * rgbHeight) { "rgb holds ${rgb.size} pixels, needs ${rgbWidth * rgbHeight}" }
    require(maskWidth > 0 && maskHeight > 0 && mask.size >= maskWidth * maskHeight) { "mask is too small" }
    val maskHasAlpha = mask.any { (it ushr 24) != 0xFF }
    val sameSize = rgbWidth == maskWidth && rgbHeight == maskHeight
    return IntArray(rgbWidth * rgbHeight) { i ->
        val m = if (sameSize) {
            mask[i]
        } else {
            val x = (i % rgbWidth) * maskWidth / rgbWidth
            val y = (i / rgbWidth) * maskHeight / rgbHeight
            mask[y * maskWidth + x]
        }
        val alpha = if (maskHasAlpha) {
            m ushr 24
        } else {
            // Rec. 601 luma, in integers.
            (((m shr 16) and 0xFF) * 299 + ((m shr 8) and 0xFF) * 587 + (m and 0xFF) * 114 + 500) / 1000
        }
        (alpha shl 24) or (rgb[i] and 0x00FFFFFF)
    }
}

/** Premultiplies ARGB pixels in place, for bitmap formats that store them that way. */
internal fun premultiply(pixels: IntArray): IntArray {
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = p ushr 24
        if (a == 0xFF) continue
        val r = ((p shr 16) and 0xFF) * a / 255
        val g = ((p shr 8) and 0xFF) * a / 255
        val b = (p and 0xFF) * a / 255
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return pixels
}

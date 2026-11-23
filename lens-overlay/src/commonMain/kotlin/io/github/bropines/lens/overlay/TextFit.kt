package io.github.bropines.lens.overlay

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Chromium never draws translated text smaller than this, in image pixels. */
internal const val MIN_FONT_PX: Float = 3f

/** The granularity of fitted sizes. Whole pixels are too coarse for small print. */
internal const val FONT_STEP_PX: Float = 0.5f

/**
 * The largest `n` in [low]..[high] for which [fits] holds, assuming it holds
 * for every smaller `n` too (binary search). When not even [low] fits, [low] is
 * returned anyway: like Chromium's `calculateFontSizePixels()`, the search has
 * a floor rather than a failure.
 */
internal inline fun largestFitting(low: Int, high: Int, fits: (Int) -> Boolean): Int {
    var lo = low
    var hi = high
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (fits(mid)) lo = mid + 1 else hi = mid - 1
    }
    return max(low, lo - 1)
}

/**
 * The largest font size between [minPx] and [maxPx], in steps of [stepPx], at
 * which [fits] holds. See [largestFitting] for the floor.
 */
internal inline fun fitFontSize(
    minPx: Float,
    maxPx: Float,
    stepPx: Float = FONT_STEP_PX,
    fits: (Float) -> Boolean,
): Float {
    val low = ceil(minPx / stepPx).toInt()
    val high = max(low, floor(maxPx / stepPx).toInt())
    return largestFitting(low, high) { fits(it * stepPx) } * stepPx
}

/** The fitted size after the user's scale and readable-size floor. */
internal fun finalFontSize(fitted: Float, textScale: Float, minFontPx: Float): Float =
    max(max(MIN_FONT_PX, minFontPx), fitted * textScale)

private val CJK_LANGUAGES = setOf("ja", "zh", "ko", "yue")
private val RTL_LANGUAGES = setOf("ar", "bal", "ckb", "dv", "fa", "he", "iw", "ji", "ks", "ps", "sd", "ug", "ur", "yi")

private fun baseLanguage(tag: String?): String = tag.orEmpty().substringBefore('-').substringBefore('_').lowercase()

/** Whether [languageTag] is written in a script that reads well in vertical columns. */
internal fun isCjkLanguage(languageTag: String?): Boolean = baseLanguage(languageTag) in CJK_LANGUAGES

/** Whether [languageTag] is written right to left. */
internal fun isRtlLanguage(languageTag: String?): Boolean = baseLanguage(languageTag) in RTL_LANGUAGES

/**
 * Whether vertical source text stays vertical in the translation. Under
 * [VerticalTextPolicy.Auto] only a CJK target does: Russian or English set in a
 * column is faithful to the source and miserable to read.
 */
internal fun keepsVertical(policy: VerticalTextPolicy, targetLanguage: String?): Boolean = when (policy) {
    VerticalTextPolicy.Keep -> true
    VerticalTextPolicy.Horizontal -> false
    VerticalTextPolicy.Auto -> isCjkLanguage(targetLanguage)
}

// Scripts written without spaces between words, where a line may break
// between any two characters: Thai, Lao, Myanmar, Khmer. CJK is covered by
// the upright ranges.
private val UNSPACED_RANGES = arrayOf(0x0E00..0x0EFF, 0x1000..0x109F, 0x1780..0x17FF)

/** Whether a line break next to [char] would be a break inside a word. */
private fun isWordChar(char: Char): Boolean =
    char.isLetterOrDigit() && !isUpright(char.code) && UNSPACED_RANGES.none { char.code in it }

/**
 * The first offset in [lineStarts] at which a line break tears a word in two,
 * or null when every break falls at a word boundary.
 *
 * [lineStarts] are the offsets in [text] at which the second and later lines
 * of a wrapped layout begin. A break is inside a word when a letter or digit
 * stands on both sides of it; breaks between CJK characters, and in the other
 * scripts written without spaces, are ordinary.
 */
internal fun splitWordAt(text: String, lineStarts: List<Int>): Int? =
    lineStarts.firstOrNull { it in 1 until text.length && isWordChar(text[it - 1]) && isWordChar(text[it]) }

/**
 * Breaks the word that a line break at [offset] tears, properly: with a hyphen
 * and a line break. The hyphen needs room on the line, so it takes the place
 * of the line's last letter, which moves down, unless that would leave a
 * single letter (or nothing) of the word behind.
 */
internal fun hyphenateAt(text: String, offset: Int): String {
    val at = if (offset >= 3 && isWordChar(text[offset - 2]) && isWordChar(text[offset - 3])) offset - 1 else offset
    return text.substring(0, at) + "-\n" + text.substring(at)
}

/** A stretch of a vertical column: [upright] glyphs stack, the rest lies on its side. */
internal data class VerticalRun(val upright: Boolean, val text: String)

// What CSS `text-orientation: mixed` keeps upright in vertical writing: kana,
// ideographs, hangul, CJK punctuation and full-width forms, plus the dashes and
// leaders that have a vertical form and so get a cell of their own.
private val UPRIGHT_RANGES = arrayOf(
    0x1100..0x11FF, 0x2010..0x2015, 0x2025..0x2026, 0x2E80..0x303F, 0x3041..0x33FF, 0x3400..0x4DBF,
    0x4E00..0x9FFF, 0xAC00..0xD7AF, 0xF900..0xFAFF, 0xFE10..0xFE4F, 0xFF00..0xFF60, 0xFFE0..0xFFE6,
    0x20000..0x3FFFF,
)

/** Marks a font's `vert` feature turns a quarter turn; drawn rotated since it cannot be asked for. */
internal const val VERTICAL_ROTATED_PUNCTUATION: String = "ー〜～‐‑‒–—―－…‥（）｛｝〔〕［］【】「」『』〈〉《》"

/** Kuten and touten hang in the upper right of their cell. */
internal const val VERTICAL_CORNER_PUNCTUATION: String = "、。，．"

internal fun isUpright(codePoint: Int): Boolean = UPRIGHT_RANGES.any { codePoint in it }

/** Splits [text] into code points, each as its own string. */
internal fun codePointStrings(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val end = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i + 2 else i + 1
        out.add(text.substring(i, end))
        i = end
    }
    return out
}

internal fun codePointOf(s: String): Int =
    if (s.length >= 2 && s[0].isHighSurrogate() && s[1].isLowSurrogate()) {
        0x10000 + ((s[0].code - 0xD800) shl 10) + (s[1].code - 0xDC00)
    } else {
        s[0].code
    }

/**
 * Splits a column's text into upright and sideways runs. Whitespace joins the
 * run before it, so a space between two kanji does not start a sideways run.
 */
internal fun verticalRuns(text: String): List<VerticalRun> {
    val runs = ArrayList<VerticalRun>()
    val current = StringBuilder()
    var currentUpright = false
    for (cp in codePointStrings(text)) {
        val upright = if (cp.isBlank() && current.isNotEmpty()) currentUpright else isUpright(codePointOf(cp))
        if (current.isNotEmpty() && upright != currentUpright) {
            runs.add(VerticalRun(currentUpright, current.toString()))
            current.clear()
        }
        currentUpright = upright
        current.append(cp)
    }
    if (current.isNotEmpty()) runs.add(VerticalRun(currentUpright, current.toString()))
    return runs
}

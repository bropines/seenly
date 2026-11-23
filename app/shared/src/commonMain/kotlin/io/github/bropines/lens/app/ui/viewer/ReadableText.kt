package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection

private val NO_SPACE_LANGUAGES = setOf("ja", "zh", "zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "th", "lo", "km", "my")

/**
 * The paragraph as prose rather than as lines on the page: Lens's line breaks
 * are where the text happened to wrap. Japanese and Chinese join without a
 * space (vertical manga columns included); other scripts join with one, and a
 * word hyphenated across the break is mended.
 */
fun Paragraph.readableText(): String {
    val noSpace = language?.let { it in NO_SPACE_LANGUAGES || it.substringBefore('-') in NO_SPACE_LANGUAGES } == true ||
        writingDirection == WritingDirection.TopToBottom
    val out = StringBuilder()
    lines.map { it.text.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        when {
            out.isEmpty() -> Unit
            noSpace -> Unit
            out.endsWith("-") && out.length > 1 && out[out.length - 2].isLetter() -> out.setLength(out.length - 1)
            else -> out.append(' ')
        }
        out.append(line)
    }
    return out.toString()
}

/** Every paragraph as prose, one per line. */
fun ScanResult.readableText(): String = paragraphs.joinToString("\n") { it.readableText() }

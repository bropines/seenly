package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/** How much of the shorter of two vertical spans the other must share to count as the same row. */
private const val ROW_OVERLAP = 0.4f

/** The top and bottom of [box] as it lies on the page, turned or not, in fractions of the page's height. */
private fun verticalSpan(box: Box): Pair<Float, Float> {
    val half = (abs(box.width * sin(box.rotation)) + abs(box.height * cos(box.rotation))) / 2
    return box.centerY - half to box.centerY + half
}

/**
 * The paragraphs in the order they are read.
 *
 * Lens lists them as it found them. For most pages that is the reading
 * order; for a page of vertical text (manga) it is not: there the bubbles are
 * read in rows from the top, and within a row from the right. So when at
 * least half the paragraphs are vertical, they are gathered into rows by how
 * much their heights overlap and each row is taken right to left. Any other
 * page, and one where a paragraph has no box, is left as Lens gave it.
 */
fun ScanResult.inReadingOrder(): List<Paragraph> {
    val vertical = paragraphs.count { it.writingDirection == WritingDirection.TopToBottom }
    if (paragraphs.size < 2 || vertical * 2 < paragraphs.size || paragraphs.any { it.box == null }) return paragraphs

    class Row(var top: Float, var bottom: Float, val members: MutableList<Paragraph>)

    val rows = mutableListOf<Row>()
    for (paragraph in paragraphs.sortedBy { verticalSpan(it.box!!).first }) {
        val (top, bottom) = verticalSpan(paragraph.box!!)
        val row = rows.lastOrNull()?.takeIf { row ->
            val shared = min(bottom, row.bottom) - max(top, row.top)
            shared > ROW_OVERLAP * min(bottom - top, row.bottom - row.top)
        }
        if (row == null) {
            rows += Row(top, bottom, mutableListOf(paragraph))
        } else {
            row.members += paragraph
            // The row is what its bubbles share: a tall bubble in it must not pull the next row in too.
            row.top = max(row.top, top)
            row.bottom = min(row.bottom, bottom)
        }
    }
    return rows.flatMap { row -> row.members.sortedByDescending { it.box!!.centerX } }
}

/** Every paragraph as prose, one per line, in reading order. */
fun ScanResult.readableText(): String = inReadingOrder().joinToString("\n") { it.readableText() }

/** Every translation, one per line, in reading order, or null if there was none. */
fun ScanResult.translatedText(): String? =
    inReadingOrder().mapNotNull { it.translation?.text }.takeIf { it.isNotEmpty() }?.joinToString("\n")

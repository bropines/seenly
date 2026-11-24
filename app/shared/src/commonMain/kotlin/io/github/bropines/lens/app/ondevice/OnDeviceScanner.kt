package io.github.bropines.lens.app.ondevice

import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ParagraphTranslation
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.WritingDirection
import io.github.bropines.lens.translate.Languages
import kotlin.math.abs

/**
 * A scan made on the device: recognize, translate, and shape the answer like
 * one from Lens, so the same overlay draws it.
 *
 * It is the lesser scan. There is no erased background and no bubble shaping
 * from the server, paragraphs are whatever the recognizer grouped, and a
 * translation is laid over its paragraph's lines in proportion to their
 * length rather than word by word.
 */
class OnDeviceScanner(private val ml: OnDeviceMl) {
    /**
     * Scans [image] with the languages of [settings]. [download] lets a
     * missing translation model be fetched; a fallback for a dead network
     * passes false. Throws [OnDeviceException] when nothing could be
     * translated for want of a model or a supported language.
     */
    suspend fun scan(image: ByteArray, settings: AppSettings, download: Boolean): ScanResult {
        // The recognizer is per script and cannot be asked to detect one, so the stated language picks it.
        val hint = settings.sourceLanguage ?: settings.ocrLanguage.ifEmpty { null }
        val read = ml.recognize(image, ocrScriptFor(hint))
        if (read.paragraphs.isEmpty()) return read

        val target = settings.targetLanguage
        val sources = read.paragraphs.map { p ->
            settings.sourceLanguage ?: p.language?.takeIf { it.isNotEmpty() && it != "und" } ?: ml.identifyLanguage(p.text)
        }
        val translations = arrayOfNulls<String>(read.paragraphs.size)
        var failure: OnDeviceException? = null
        for ((source, indices) in read.paragraphs.indices.groupBy { sources[it] }) {
            // Text already in the target language, or in none we can name, stays as it is.
            if (source == null || Languages.base(Languages.normalize(source)) == Languages.base(Languages.normalize(target))) continue
            try {
                val out = ml.translate(indices.map { read.paragraphs[it].text }, source, target, download, settings.onDevice.wifiOnly)
                indices.forEachIndexed { n, index -> translations[index] = out[n] }
            } catch (e: OnDeviceException) {
                if (failure == null) failure = e
            }
        }
        failure?.let { if (translations.all { it == null }) throw it }

        return read.copy(
            language = sources.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: read.language,
            paragraphs = read.paragraphs.mapIndexed { i, p ->
                val text = translations[i]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexed p
                p.withTranslation(text, sources[i], target)
            },
        )
    }

    private fun Paragraph.withTranslation(text: String, source: String?, target: String): Paragraph {
        val shares = distributeOverLines(text, lines.map { it.text.length })
        return copy(
            lines = lines.mapIndexed { i, line -> line.copy(translation = shares[i].ifEmpty { null }) },
            translation = ParagraphTranslation(
                text = text,
                sourceLanguage = source,
                targetLanguage = target,
                writingDirection = if (Languages.base(target) in RIGHT_TO_LEFT) WritingDirection.RightToLeft else WritingDirection.LeftToRight,
                alignment = TextAlignment.Start,
                justified = false,
            ),
        )
    }

    private companion object {
        val RIGHT_TO_LEFT = setOf("ar", "he", "fa", "ur", "yi", "ps", "sd", "ug")
    }
}

/**
 * Cuts [translation] into one piece per line, sized in proportion to
 * [weights] (the source lines' lengths), breaking at spaces where it has any.
 *
 * As with Lens's own line translations, a piece keeps the space after its
 * last word, so the pieces concatenate back into [translation] exactly.
 */
internal fun distributeOverLines(translation: String, weights: List<Int>): List<String> {
    if (weights.isEmpty()) return emptyList()
    if (weights.size == 1) return listOf(translation)
    // Words with the space that follows them; text without spaces (Chinese, Japanese) breaks anywhere.
    val tokens = if (' ' in translation) Regex("""\S+\s*|\s+""").findAll(translation).map { it.value }.toList() else translation.map { it.toString() }
    val total = weights.sumOf { it.coerceAtLeast(1) }.toFloat()
    val pieces = MutableList(weights.size) { StringBuilder() }
    var line = 0
    var written = 0
    var boundary = weights[0].coerceAtLeast(1) / total * translation.length
    for (token in tokens) {
        // Move on when this line's share is nearer before the token than after it.
        while (line < weights.lastIndex && pieces[line].isNotEmpty() && abs(written - boundary) <= abs(written + token.length - boundary)) {
            line++
            boundary += weights[line].coerceAtLeast(1) / total * translation.length
        }
        pieces[line].append(token)
        written += token.length
    }
    return pieces.map { it.toString() }
}

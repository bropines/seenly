package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.viewer.overlayStyle
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.TranslatedImage
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.LineStyle
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ParagraphTranslation
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.Word
import io.github.bropines.lens.protocol.WritingDirection
import org.jetbrains.compose.resources.stringResource

/*
 * The overlay settings shown at work: a small made-up picture with its
 * "translation" drawn over it by the same code, and with the same style, as a
 * real page in the viewer. Nothing is scanned and nothing leaves the device:
 * the picture is painted here and its Lens answer is written by hand.
 */

private enum class Sample { Sign, Manga }

/** A sample picture and the scan result that goes with it. */
private class SamplePage(val image: ImageBitmap, val result: ScanResult)

/**
 * The live preview on the Overlay page. Two samples, because the layouts
 * differ by kind of picture: a sign set line by line, and a manga bubble with
 * vertical text, which the automatic layout reflows.
 */
@Composable
internal fun OverlayPreview(settings: AppSettings) {
    var sample by rememberSaveable { mutableStateOf(Sample.Sign) }
    var original by rememberSaveable { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val sign = listOf(
        stringResource(Res.string.preview_sign_1),
        stringResource(Res.string.preview_sign_2),
        stringResource(Res.string.preview_sign_3),
        stringResource(Res.string.preview_sign_hours),
    )
    val manga = listOf(
        stringResource(Res.string.preview_manga_1a),
        stringResource(Res.string.preview_manga_1b),
        stringResource(Res.string.preview_manga_2),
    )
    val page = remember(sample, sign, manga, measurer) {
        when (sample) {
            Sample.Sign -> signSample(measurer, sign)
            Sample.Manga -> mangaSample(measurer, manga)
        }
    }
    val style = settings.overlayStyle(page.result).copy(highlightColor = MaterialTheme.colorScheme.primary)

    SettingsCard {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            TranslatedImage(
                image = page.image,
                result = page.result,
                mode = if (original) OverlayMode.Original else OverlayMode.Translated,
                style = style,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            )
        }
        ChipRow(
            listOf(Sample.Sign to stringResource(Res.string.preview_sign), Sample.Manga to stringResource(Res.string.preview_manga)),
            sample,
            Modifier.padding(horizontal = 12.dp),
            trailing = {
                FilterChip(
                    selected = original,
                    onClick = { original = !original },
                    label = { Text(stringResource(Res.string.original), maxLines = 1) },
                    shape = MaterialTheme.shapes.small,
                )
            },
        ) { sample = it }
        Footnote(stringResource(Res.string.preview_hint), Modifier.padding(bottom = 8.dp))
    }
}

private const val WIDTH = 800
private const val HEIGHT = 450

private val Board = Color(0xFF2E4A3D)
private val Chalk = Color(0xFFF4F1E6)
private val Ink = Color(0xFF1E1E1E)
private val Paper = Color(0xFFFFFFFF)

/** Paints a [WIDTH] x [HEIGHT] picture. One unit is one pixel, so sizes in sp are sizes in pixels. */
private fun paint(block: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(WIDTH, HEIGHT)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(WIDTH.toFloat(), HEIGHT.toFloat()), block)
    return image
}

private fun Rect.normalised() = Box(
    centerX = center.x / WIDTH,
    centerY = center.y / HEIGHT,
    width = width / WIDTH,
    height = height / HEIGHT,
    rotation = 0f,
)

/** A line as Lens reports one: its text as a single word, its box, its share of the translation. */
private fun line(text: String, area: Rect, translation: String, textColor: Color, background: Color) = Line(
    text = text,
    words = listOf(Word(text, separator = "", box = area.normalised())),
    box = area.normalised(),
    // Lens keeps the separator after a line's last word, which is what joins the lines back up.
    translation = "$translation ",
    translationStyle = LineStyle(textColor = textColor.toArgb(), backgroundColor = background.toArgb(), background = null),
)

private fun paragraph(lines: List<Pair<Line, Rect>>, direction: WritingDirection, alignment: TextAlignment): Paragraph {
    val area = lines.map { it.second }.reduce { all, next ->
        Rect(minOf(all.left, next.left), minOf(all.top, next.top), maxOf(all.right, next.right), maxOf(all.bottom, next.bottom))
    }
    return Paragraph(
        text = lines.joinToString("\n") { it.first.text },
        lines = lines.map { it.first },
        box = area.normalised(),
        language = "ja",
        writingDirection = direction,
        translation = ParagraphTranslation(
            text = lines.joinToString("") { it.first.translation.orEmpty() }.trim(),
            sourceLanguage = "ja",
            // Only its script matters here (vertical text stays vertical for a CJK target); the samples are never CJK.
            targetLanguage = "en",
            writingDirection = WritingDirection.LeftToRight,
            alignment = alignment,
            justified = false,
        ),
    )
}

/** A cafe's chalkboard: three lines of one size, and a line of fine print for the minimum size to act on. */
private fun signSample(measurer: TextMeasurer, translations: List<String>): SamplePage {
    val boxes = ArrayList<Rect>()
    val source = listOf("本日のおすすめ" to 52, "焼き魚定食　850円" to 52, "ご飯・味噌汁付き" to 52, "営業時間 11:00〜22:00" to 26)
    val tops = listOf(92f, 168f, 244f, 342f)
    val image = paint {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFE9DCC3), Color(0xFFCDB592))))
        drawRoundRect(Color(0xFF6B4E33), Offset(48f, 40f), Size(704f, 370f), CornerRadius(22f))
        drawRoundRect(Board, Offset(62f, 54f), Size(676f, 342f), CornerRadius(12f))
        source.forEachIndexed { index, (text, size) ->
            val layout = measurer.measure(text, TextStyle(color = Chalk, fontSize = size.sp, fontWeight = FontWeight.Medium), density = Density(1f))
            val at = Offset(110f, tops[index])
            drawText(layout, topLeft = at)
            boxes += Rect(at, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
        }
    }
    val lines = source.indices.map { line(source[it].first, boxes[it], translations[it], Chalk, Board) to boxes[it] }
    return SamplePage(
        image,
        ScanResult(
            paragraphs = listOf(
                paragraph(lines.take(3), WritingDirection.LeftToRight, TextAlignment.Start),
                paragraph(lines.drop(3), WritingDirection.LeftToRight, TextAlignment.Start),
            ),
            language = "ja",
            imageWidth = WIDTH,
            imageHeight = HEIGHT,
        ),
    )
}

/** A manga panel: two speech bubbles of vertical text, columns read right to left. */
private fun mangaSample(measurer: TextMeasurer, translations: List<String>): SamplePage {
    // Each column: its text, the x of its centre, the y of its first character.
    val columns = listOf(Triple("やっと", 598f, 120f), Triple("見つけた！", 526f, 100f), Triple("えっ？", 232f, 232f))
    val glyph = 42
    val step = 46f
    val boxes = ArrayList<Rect>()
    val image = paint {
        drawRect(Paper)
        drawRect(Brush.linearGradient(listOf(Color(0xFFD9DCE3), Color(0xFFAEB4C0))), Offset(28f, 24f), Size(744f, 402f))
        // Speed lines, so the panel is not an empty grey box.
        for (i in 0..14) drawLine(Color(0x33000000), Offset(28f, 60f + i * 26f), Offset(300f + i * 14f, 24f), strokeWidth = 2f)
        drawRect(Ink, Offset(28f, 24f), Size(744f, 402f), style = Stroke(6f))
        for ((centre, radii) in listOf(Offset(562f, 214f) to Size(160f, 165f), Offset(232f, 300f) to Size(104f, 112f))) {
            val corner = Offset(centre.x - radii.width, centre.y - radii.height)
            val size = Size(radii.width * 2, radii.height * 2)
            drawOval(Paper, corner, size)
            drawOval(Ink, corner, size, style = Stroke(5f))
        }
        for ((text, centreX, top) in columns) {
            text.forEachIndexed { index, char ->
                val layout = measurer.measure(char.toString(), TextStyle(color = Ink, fontSize = glyph.sp, fontWeight = FontWeight.Medium), density = Density(1f))
                drawText(layout, topLeft = Offset(centreX - layout.size.width / 2f, top + index * step + (step - layout.size.height) / 2f))
            }
            boxes += Rect(centreX - glyph / 2f, top, centreX + glyph / 2f, top + text.length * step)
        }
    }
    val lines = columns.indices.map { line(columns[it].first, boxes[it], translations[it], Ink, Paper) to boxes[it] }
    return SamplePage(
        image,
        ScanResult(
            paragraphs = listOf(
                paragraph(lines.take(2), WritingDirection.TopToBottom, TextAlignment.Center),
                paragraph(lines.drop(2), WritingDirection.TopToBottom, TextAlignment.Center),
            ),
            language = "ja",
            imageWidth = WIDTH,
            imageHeight = HEIGHT,
        ),
    )
}

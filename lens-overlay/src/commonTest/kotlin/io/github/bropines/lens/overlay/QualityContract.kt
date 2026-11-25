package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.measureTime

/** A page of the corpus: a picture and what Lens answered for it, both in the fixtures folder. */
internal class QualityPage(val name: String, val image: String, val answer: String)

/** The styles the app draws with: a preset with the user's default refinements on top. */
internal object AppStyles {
    private fun OverlayStyle.refined() = copy(
        uniformParagraphSize = LayoutChoice.Auto,
        growFromLineStart = LayoutChoice.Auto,
        reflowDenseParagraphs = LayoutChoice.Auto,
    )

    /** "In place": signs, screenshots, documents. */
    val InPlace: OverlayStyle = OverlayStyle.Chromium.refined()

    /** "Bubbles": the manga preset as the viewer uses it, grown a little less. */
    val Bubbles: OverlayStyle = OverlayStyle.Manga.copy(reflowGrowth = 1.25f).refined()

    /** The viewer's own rule under "Auto": vertical source text means a comic page. */
    fun isComic(result: ScanResult): Boolean = result.paragraphs.any { it.writingDirection == WritingDirection.TopToBottom }

    /**
     * The readable floor in image pixels for [image] shown fitted in a phone's
     * 392 x 760 dp viewer, as [TranslatedImage] computes it (sp == dp here).
     */
    fun phoneFloor(image: ImageBitmap, style: OverlayStyle): Float {
        val scale = min(392f / image.width, 760f / image.height)
        return (style.minFontSp / scale * 2).roundToInt() / 2f
    }
}

internal object QualityCorpus {
    /**
     * Real Lens answers, saved by the CLI. The demo pictures are drawn by
     * `scripts/demo_images.py`, the `page-` ones by `fixtures/draw_pages.py`.
     */
    val pages: List<QualityPage> = listOf(
        QualityPage("manga1-ru", "manga1.png", "manga1.ru"),
        QualityPage("demo-manga-ru", "demo-manga.png", "demo-manga.ru"),
        QualityPage("demo-manga-en", "demo-manga.png", "demo-manga.en"),
        QualityPage("phone-ru", "tall1.jpg", "tall1.ru"),
        QualityPage("phone-en", "tall1.jpg", "tall1.en"),
        QualityPage("ui-en", "s1.jpg", "s1.en"),
        QualityPage("menu-ru", "demo-menu.jpg", "demo-menu.ru"),
        QualityPage("menu-en", "demo-menu.jpg", "demo-menu.en"),
        QualityPage("notice-ru", "demo-notice.jpg", "demo-notice.ru"),
        QualityPage("notice-en", "demo-notice.jpg", "demo-notice.en"),
        QualityPage("document-ru", "page-document.png", "page-document.ru"),
        QualityPage("monitor-ru", "page-monitor.jpg", "page-monitor.ru"),
        QualityPage("settings-ru", "page-settings.png", "page-settings.ru"),
        QualityPage("manga2-ru", "page-manga2.png", "page-manga2.ru"),
        QualityPage("manga2-en", "page-manga2.png", "page-manga2.en"),
        QualityPage("novel-ru", "page-novel.png", "page-novel.ru"),
    )
}

/**
 * The visual regression harness: every page of the corpus is laid out and
 * painted as the app would, measured ([PageQuality]) and kept as a picture.
 *
 * The pictures and `report.tsv` land in `build/overlay-quality/<backend>`:
 * for each page and style the result, and the result again with every counted
 * defect coloured in (red: text on something the erasing left; yellow: two
 * paragraphs through each other; magenta: source ink still there; orange:
 * artwork wiped; blue: a seam around a fill).
 */
abstract class QualityContract {
    protected abstract val backend: RenderBackend

    private val json = Json { ignoreUnknownKeys = true }

    private fun measure(page: QualityPage): List<MeasuredPage> {
        val image = backend.readFixture(page.image)?.let(::decodeEncodedImage) ?: return emptyList()
        val answer = backend.readFixture("${page.answer}.json") ?: return emptyList()
        val result = json.decodeFromString(ScanResult.serializer(), answer.decodeToString())
        val state = backend.blocking { decodeOverlay(result, backend.fonts) }
        saveDetail(page.name, image)
        val comic = AppStyles.isComic(result)
        // The style the app would pick is starred; the other is what the user gets by overriding it.
        val styles = listOf(
            (if (comic) "bubbles*" else "bubbles") to AppStyles.Bubbles,
            (if (comic) "inplace" else "inplace*") to AppStyles.InPlace,
        )
        return styles.map { (label, style) ->
            val floor = AppStyles.phoneFloor(image, style)
            val trace = StringBuilder()
            var scene = buildOverlayScene(state, result, style, image, floor) { trace.append(it).append('\n') }
            backend.save(DIRECTORY, "${page.name}-${label.trimEnd('*')}-trace.log", trace.toString().encodeToByteArray())
            // The first layout pays for fonts and caches; the best of the next three is the cost.
            val ms = (0 until 3).minOf {
                measureTime { scene = buildOverlayScene(state, result, style, image, floor) }.inWholeMicroseconds / 1000.0
            }
            measurePage(page.name, label, image, result, scene, floor, ms).also { measured ->
                val file = "${page.name}-${label.trimEnd('*')}"
                backend.save(DIRECTORY, "$file.png", backend.encodePng(measured.picture))
                val diagnostic = imageBitmapFromArgb(measured.diagnostic, image.width, image.height)
                backend.save(DIRECTORY, "$file-diag.png", backend.encodePng(diagnostic))
            }
        }
    }

    /** The picture washed out, with everything the layout takes for "something is drawn here" in red. */
    private fun saveDetail(name: String, image: ImageBitmap) {
        val map = detailOf(image.forSoftwareCanvas()) ?: return
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h).also { image.readPixels(it) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                pixels[i] = if (!map.plain(Rect(x + 0.25f, y + 0.25f, x + 0.75f, y + 0.75f))) {
                    0xFFE00000.toInt()
                } else {
                    val r = (((p shr 16) and 0xFF) + 255 * 2) / 3
                    val g = (((p shr 8) and 0xFF) + 255 * 2) / 3
                    val b = ((p and 0xFF) + 255 * 2) / 3
                    ARGB_BLACK or (r shl 16) or (g shl 8) or b
                }
            }
        }
        backend.save(DIRECTORY, "$name-detail.png", backend.encodePng(imageBitmapFromArgb(pixels, w, h)))
    }

    @Test
    fun `the corpus is rendered, measured and reported`() {
        if (!backend.available) return
        val rows = QualityCorpus.pages.flatMap(::measure).map { it.quality }
        val table = listOf(PageQuality.HEADER) + rows.map { it.cells() }
        backend.save(DIRECTORY, "report.tsv", table.joinToString("\n") { it.joinToString("\t") }.encodeToByteArray())
        val widths = PageQuality.HEADER.indices.map { column -> table.maxOf { it[column].length } }
        println(table.joinToString("\n") { row -> row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ") })
        // Every page was there to measure, under both styles.
        rows.size shouldBe QualityCorpus.pages.size * 2
    }

    private companion object {
        const val DIRECTORY = "overlay-quality"
    }
}

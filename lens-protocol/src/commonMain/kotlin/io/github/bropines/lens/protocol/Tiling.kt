package io.github.bropines.lens.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Scans a tall image in horizontal bands instead of in one piece.
 *
 * Chromium shrinks an upload that is over 1.5 MP *and* over 1600 px on a
 * side, so a phone screenshot (1280×2772) reaches Lens at about 739×1600 and a
 * webtoon strip (720×10000) at 115×1600, where small text and manga columns
 * blur away. A band no taller than 1600 px on an image no wider than that is
 * never shrunk. Bands overlap so a line cut by one band's edge is whole in the
 * next; each paragraph is kept from the band that owns its centre (the halves
 * of each overlap are split between neighbours).
 */
public object Tiling {
    /** Overlap between neighbouring bands, in pixels: about two lines of body text. */
    public const val OVERLAP: Int = 160

    /** Beyond this many bands a strip is scanned in fewer, taller (so shrunk) bands: requests cost time. */
    public const val MAX_BANDS: Int = 10

    /** The bands' tops and their common height, or null when one upload keeps full resolution. */
    public fun bands(width: Int, height: Int): Bands? {
        if (!ImagePreparer.shouldDownscale(width, height) || width > LensDefaults.MAX_SIDE) return null
        var band = LensDefaults.MAX_SIDE
        var count = ceil((height - OVERLAP).toDouble() / (band - OVERLAP)).toInt().coerceAtLeast(2)
        if (count > MAX_BANDS) {
            count = MAX_BANDS
            band = ceil((height + OVERLAP * (count - 1)).toDouble() / count).toInt()
        }
        val step = (height - band).toDouble() / (count - 1)
        return Bands(List(count) { (it * step).toInt() }, band)
    }

    public class Bands(public val tops: List<Int>, public val height: Int)

    /**
     * Scans [raster] band by band with [scanOne] (at most [parallel] at a time)
     * and returns one result for the whole image, boxes normalised to it.
     */
    public suspend fun scan(raster: Raster, parallel: Int = 3, scanOne: suspend (Raster) -> ScanResult): ScanResult {
        val plan = bands(raster.width, raster.height) ?: return scanOne(raster)
        val gate = Semaphore(parallel)
        val parts = coroutineScope {
            plan.tops.map { top ->
                async { gate.withPermit { scanOne(withContext(Dispatchers.Default) { crop(raster, top, plan.height) }) } }
            }.awaitAll()
        }
        return merge(parts, plan, raster.width, raster.height)
    }

    /** Rows [top] until [top] + [height] of [raster]. */
    public fun crop(raster: Raster, top: Int, height: Int): Raster {
        val rows = height.coerceAtMost(raster.height - top)
        return Raster(raster.width, rows, raster.argb.copyOfRange(top * raster.width, (top + rows) * raster.width))
    }

    internal fun merge(parts: List<ScanResult>, plan: Bands, width: Int, height: Int): ScanResult {
        val tops = plan.tops
        val bounds = tops.indices.map { i ->
            val lo = if (i == 0) 0f else (tops[i - 1] + plan.height + tops[i]) / 2f
            val hi = if (i == tops.lastIndex) height.toFloat() else (tops[i] + plan.height + tops[i + 1]) / 2f
            lo to hi
        }
        val paragraphs = parts.flatMapIndexed { i, part ->
            val (lo, hi) = bounds[i]
            // The last band may be cut short by the image's bottom edge.
            val rows = plan.height.coerceAtMost(height - tops[i])
            part.paragraphs
                .map { it.toWhole(tops[i], rows, height) }
                .filter { p -> p.box?.let { it.centerY * height in lo..<hi } ?: (i == 0) }
        }
        return ScanResult(
            paragraphs = paragraphs,
            language = parts.firstNotNullOfOrNull { it.language },
            imageWidth = width,
            imageHeight = height,
        )
    }

    /** A box normalised to a band, normalised to the whole image instead; x and width are unchanged. */
    private fun Box.toWhole(top: Int, band: Int, full: Int) = copy(
        centerY = (top + centerY * band) / full,
        height = height * band / full,
    )

    private fun Paragraph.toWhole(top: Int, band: Int, full: Int) = copy(
        box = box?.toWhole(top, band, full),
        lines = lines.map { line ->
            line.copy(
                box = line.box?.toWhole(top, band, full),
                words = line.words.map { word -> word.copy(box = word.box?.toWhole(top, band, full)) },
            )
        },
    )
}

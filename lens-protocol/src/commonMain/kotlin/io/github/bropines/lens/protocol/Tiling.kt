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
 * never shrunk.
 *
 * Where a band ends matters: Lens groups a manga bubble's columns into one
 * sentence only when it sees the whole panel. So bands are cut, where they
 * can be, in the middle of the longest run of plain rows (a gutter between
 * panels, a gap between paragraphs or UI blocks). Where no plain row is in
 * reach, bands overlap so a line cut by one band's edge is whole in the next.
 * Each paragraph is kept from the band that owns its centre.
 */
public object Tiling {
    /** Overlap between neighbouring bands with no plain row to cut at, in pixels: about two lines of body text. */
    public const val OVERLAP: Int = 160

    /** Beyond this many bands a strip is scanned in fewer, taller (so shrunk) bands: requests cost time. */
    public const val MAX_BANDS: Int = 10

    /** Bands with no shared seam, cut at plain rows where possible; null when one upload keeps full resolution. */
    public fun plan(raster: Raster): Bands? {
        val uniform = bands(raster.width, raster.height) ?: return null
        return cutAtPlainRows(raster) ?: uniform
    }

    /** Evenly spaced overlapping bands, or null when one upload keeps full resolution. */
    public fun bands(width: Int, height: Int): Bands? {
        if (!ImagePreparer.shouldDownscale(width, height) || width > LensDefaults.MAX_SIDE) return null
        var band = LensDefaults.MAX_SIDE
        var count = ceil((height - OVERLAP).toDouble() / (band - OVERLAP)).toInt().coerceAtLeast(2)
        if (count > MAX_BANDS) {
            count = MAX_BANDS
            band = ceil((height + OVERLAP * (count - 1)).toDouble() / count).toInt()
        }
        val step = (height - band).toDouble() / (count - 1)
        return Bands(List(count) { (it * step).toInt() }, List(count) { band })
    }

    /** Each band's first row and its height. Neighbours may overlap. */
    public class Bands(public val tops: List<Int>, public val heights: List<Int>) {
        public fun bottom(i: Int): Int = tops[i] + heights[i]
    }

    /**
     * Greedy: each band runs at most 1600 px, ending in the longest plain run
     * in its lower half; failing one, it ends at 1600 and the next overlaps.
     * Null when that takes more than [MAX_BANDS] bands.
     */
    internal fun cutAtPlainRows(raster: Raster): Bands? {
        val max = LensDefaults.MAX_SIDE
        val plain = BooleanArray(raster.height) { isPlain(raster, it) }
        val tops = mutableListOf<Int>()
        val heights = mutableListOf<Int>()
        var top = 0
        while (raster.height - top > max) {
            if (tops.size == MAX_BANDS - 1) return null
            val cut = longestRunMiddle(plain, top + max / 2, top + max)
            tops += top
            if (cut != null) {
                heights += cut - top
                top = cut
            } else {
                heights += max
                top += max - OVERLAP
            }
        }
        tops += top
        heights += raster.height - top
        return Bands(tops, heights)
    }

    /** The middle of the longest run of plain rows within [from, to), the later one on a tie. */
    private fun longestRunMiddle(plain: BooleanArray, from: Int, to: Int): Int? {
        var best: IntRange? = null
        var start = -1
        for (y in from..to) {
            val on = y < to && plain[y]
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                if (best == null || y - start >= best.last - best.first + 1) best = start..<y
                start = -1
            }
        }
        return best?.let { (it.first + it.last + 1) / 2 }
    }

    /** A row of one colour, give or take compression noise: nothing on it to cut through. */
    private fun isPlain(raster: Raster, y: Int): Boolean {
        val row = y * raster.width
        var lo = 255
        var hi = 0
        var x = 0
        while (x < raster.width) {
            val p = raster.argb[row + x]
            val l = ((p shr 16 and 0xFF) + 2 * (p shr 8 and 0xFF) + (p and 0xFF)) / 4
            if (l < lo) lo = l
            if (l > hi) hi = l
            if (hi - lo > PLAIN_SPREAD) return false
            x += 2
        }
        return true
    }

    /** How far luminance may wander along a plain row (JPEG noise around a flat gutter). */
    private const val PLAIN_SPREAD = 24

    /**
     * Scans [raster] band by band with [scanOne] (at most [parallel] at a time)
     * and returns one result for the whole image, boxes normalised to it.
     */
    public suspend fun scan(raster: Raster, parallel: Int = 3, scanOne: suspend (Raster) -> ScanResult): ScanResult {
        val plan = withContext(Dispatchers.Default) { plan(raster) } ?: return scanOne(raster)
        val gate = Semaphore(parallel)
        val parts = coroutineScope {
            plan.tops.indices.map { i ->
                async {
                    gate.withPermit {
                        scanOne(withContext(Dispatchers.Default) { crop(raster, plan.tops[i], plan.heights[i]) })
                    }
                }
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
        // Neighbours split their overlap in half; bands that meet at a cut split at the cut.
        val bounds = tops.indices.map { i ->
            val lo = if (i == 0) 0f else (plan.bottom(i - 1) + tops[i]) / 2f
            val hi = if (i == tops.lastIndex) height.toFloat() else (plan.bottom(i) + tops[i + 1]) / 2f
            lo to hi
        }
        val paragraphs = parts.flatMapIndexed { i, part ->
            val (lo, hi) = bounds[i]
            // The last band may be cut short by the image's bottom edge.
            val rows = plan.heights[i].coerceAtMost(height - tops[i])
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

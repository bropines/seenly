package io.github.bropines.lens.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.WritingDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// How good a translated page looks, in numbers. Nothing here knows what the
// page "should" look like: every figure is read off the picture before
// erasing, after erasing and after the text is drawn, so it works on real
// Lens answers as well as on drawn test pages.
//
// Less is better for every figure but the contrast.

/** A brightness step between neighbouring pixels that is a drawn edge, not noise. */
private const val DETAIL_STEP = 40

/** A source pixel this far from its line's background is ink. */
private const val GLYPH_GAP = 64

/** Under this, a pixel is what it was before the erasing. */
private const val UNCHANGED_GAP = 24

/** Over this, the erasing changed a pixel at all. */
private const val CHANGED_GAP = 6

/** A step the erasing left where the picture had none, large enough to see. */
private const val SEAM_STEP = 24

/** Art counts as wiped when the erasing moved it at least this far. */
private const val DAMAGE_GAP = 48

/** Coverage from which a pixel counts as inked by text. */
private const val INK_ALPHA = 96

/** Paragraphs whose source lettering is within this factor of each other are peers: one voice, one size. */
private const val PEER_SIZE = 1.15f

/** Peers set further apart than this are visibly uneven. */
private const val PEER_UNEVEN = 1.25f

/** Text closer than this to something drawn, in font sizes, is crowded against it. */
private const val CROWD_REACH = 0.3f

/** A paragraph with this many crowded pixels touches what it is crowded against; fewer is a stray edge. */
private const val TOUCH_PX = 4

internal data class PageQuality(
    val page: String,
    val style: String,
    /** The readable-size floor the page was laid out with, in image pixels. */
    val floorPx: Float,
    /** Paragraphs that had text drawn for them, and the drawing operations that took. */
    val paragraphs: Int,
    val textOps: Int,
    /** One layout of the page, in milliseconds. */
    val layoutMs: Double,
    val inkPx: Int,
    /** Pixels inked by two paragraphs at once (fill and outline both). */
    val overlapPx: Int,
    /** The share of text ink that lies on something the erasing left: an outline, artwork, other lettering. */
    val inkOverDetail: Double,
    /** The share of text ink within [CROWD_REACH] font sizes of such a thing: text squeezed against an outline. */
    val crowded: Double,
    /** How many paragraphs come that close to something drawn: one letter against an outline is one too many. */
    val touching: Int,
    /** The share of text ink that falls outside the picture. */
    val clipped: Double,
    /** The share of characters set under the readable floor. */
    val belowFloor: Double,
    /** How readable the page is: each character's size against the floor, at most 1, averaged. 1 is all readable. */
    val legibility: Double,
    /** The size the middle character of the page is set at, in image pixels. */
    val medianFont: Float,
    /** The largest ratio between the sizes of two lines of one paragraph. */
    val lineJump: Double,
    /** The largest ratio between the sizes of two paragraphs whose source lettering is one size. */
    val peerJump: Double,
    /** The share of such pairs set more than [PEER_UNEVEN] apart. */
    val peerUneven: Double,
    /** How far a line starts before its start-aligned paragraph's edge, at worst, in line heights. */
    val edgeSpill: Double,
    /** The lowest WCAG contrast between a paragraph's text and what it stands on. */
    val minContrast: Double,
    val glyphPx: Int,
    /** The share of the source's ink still there after erasing. */
    val residual: Double,
    /** Pixels of artwork the erasing wiped, per thousand pixels of source ink. */
    val artDamage: Double,
    /** Pixels of visible seam the erasing left on plain background, per thousand pixels of source ink. */
    val seam: Double,
) {
    fun cells(): List<String> = listOf(
        page, style, fmt(floorPx.toDouble(), 0), paragraphs.toString(), textOps.toString(), fmt(layoutMs, 1),
        overlapPx.toString(), pct(inkOverDetail), pct(crowded), touching.toString(), pct(clipped), pct(belowFloor), fmt(legibility, 2),
        fmt(medianFont.toDouble(), 1), fmt(lineJump, 2), fmt(peerJump, 2),
        pct(peerUneven), fmt(edgeSpill, 2), fmt(minContrast, 1), pct(residual), fmt(artDamage, 1), fmt(seam, 1),
    )

    companion object {
        val HEADER: List<String> = listOf(
            "page", "style", "floor", "pars", "ops", "ms",
            "overlap", "onDetail%", "crowded%", "touch", "clipped%", "small%", "legib", "font", "lineJump", "peerJump",
            "uneven%", "spill", "contrast", "residual%", "damage", "seam",
        )

        private fun fmt(value: Double, digits: Int): String {
            var scale = 1.0
            repeat(digits) { scale *= 10 }
            val rounded = (value * scale).roundToInt() / scale
            return if (digits == 0) rounded.roundToInt().toString() else rounded.toString()
        }

        private fun pct(value: Double): String = fmt(value * 100, 2)
    }
}

/** A measured page: its figures, the finished picture, and the same picture with every counted defect coloured in. */
internal class MeasuredPage(val quality: PageQuality, val picture: ImageBitmap, val diagnostic: IntArray)

internal fun channelGap(a: Int, b: Int): Int = max(
    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
    max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
)

private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

/** Marks both pixels of every neighbouring pair whose brightness differs by [step] or more. */
internal fun detailMap(argb: IntArray, width: Int, height: Int, step: Int = DETAIL_STEP): BooleanArray {
    val brightness = IntArray(argb.size) { luma(argb[it]) }
    val out = BooleanArray(argb.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            if (x + 1 < width && abs(brightness[i] - brightness[i + 1]) >= step) {
                out[i] = true
                out[i + 1] = true
            }
            if (y + 1 < height && abs(brightness[i] - brightness[i + width]) >= step) {
                out[i] = true
                out[i + width] = true
            }
        }
    }
    return out
}

/** The commonest colour among the first [count] of [pixels]: the mean of the fullest 16-level bin. Null for none. */
internal fun modeColor(pixels: IntArray, count: Int = pixels.size): Int? {
    if (count <= 0) return null
    fun bin(p: Int): Int = (((p shr 20) and 0xF) shl 8) or (((p shr 12) and 0xF) shl 4) or ((p shr 4) and 0xF)
    val bins = IntArray(4096)
    for (i in 0 until count) bins[bin(pixels[i])]++
    var top = 0
    for (b in 1 until bins.size) if (bins[b] > bins[top]) top = b
    var red = 0L
    var green = 0L
    var blue = 0L
    var members = 0
    for (i in 0 until count) {
        val p = pixels[i]
        if (bin(p) != top) continue
        red += (p shr 16) and 0xFF
        green += (p shr 8) and 0xFF
        blue += p and 0xFF
        members++
    }
    return ARGB_BLACK or ((red / members).toInt() shl 16) or ((green / members).toInt() shl 8) or (blue / members).toInt()
}

private fun median(values: List<Float>): Float = values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

/** Where [point], given in the frame an op is drawn in, lands in the image. */
private fun TextOp.toImage(point: Offset): Offset {
    val turned = if (quarterTurn) Offset(topLeft.x - (point.y - topLeft.y), topLeft.y + (point.x - topLeft.x)) else point
    val angle = rotationDegrees * PI / 180
    val c = cos(angle).toFloat()
    val s = sin(angle).toFloat()
    val x = turned.x - pivot.x
    val y = turned.y - pivot.y
    return Offset(pivot.x + x * c - y * s, pivot.y + x * s + y * c)
}

/** The axis-aligned rectangle an op's layout box covers in the image. */
internal fun TextOp.bounds(): Rect {
    val w = fill.size.width.toFloat()
    val h = fill.size.height.toFloat()
    val corners = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h)).map { toImage(topLeft + it) }
    return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

/** Which pixels of a window of the image some text inks. The window may reach outside the image. */
private class InkMap(val left: Int, val top: Int, val width: Int, val height: Int, val inked: BooleanArray) {
    inline fun forEach(action: (x: Int, y: Int) -> Unit) {
        for (i in inked.indices) if (inked[i]) action(left + i % width, top + i / width)
    }
}

private fun inkOf(scene: OverlayScene, ops: List<TextOp>, outlines: Boolean): InkMap {
    var bounds = ops.first().bounds()
    for (op in ops) {
        val b = op.bounds()
        bounds = Rect(min(bounds.left, b.left), min(bounds.top, b.top), max(bounds.right, b.right), max(bounds.bottom, b.bottom))
    }
    // Glyphs overhang their layout box a little, and an outline is stroked around them.
    val margin = ops.maxOf { it.fontSize } * 0.5f + 4f
    val left = floor(bounds.left - margin).toInt()
    val top = floor(bounds.top - margin).toInt()
    val width = (ceil(bounds.right + margin).toInt() - left).coerceIn(1, 8192)
    val height = (ceil(bounds.bottom + margin).toInt() - top).coerceIn(1, 8192)
    val drawn = if (outlines) {
        ops
    } else {
        ops.map { TextOp(it.paragraph, it.pivot, it.rotationDegrees, it.topLeft, it.quarterTurn, it.fill, null, it.fontSize) }
    }
    val alone = OverlayScene(scene.source, scene.imageWidth, scene.imageHeight, emptyList(), drawn)
    val bitmap = drawBitmap(width, height) {
        translate(-left.toFloat(), -top.toFloat()) { drawOverlayScene(alone) }
    }
    val pixels = IntArray(width * height).also { bitmap.readPixels(it) }
    return InkMap(left, top, width, height, BooleanArray(pixels.size) { (pixels[it] ushr 24) >= INK_ALPHA })
}

/** Calls [action] with the index of every pixel of a [width] x [height] image whose centre lies in [box]. */
private inline fun forEachPixelIn(box: OrientedBox, width: Int, height: Int, action: (Int) -> Unit) {
    val bounds = box.bounds()
    val x0 = floor(bounds.left).toInt().coerceIn(0, width)
    val x1 = ceil(bounds.right).toInt().coerceIn(0, width)
    val y0 = floor(bounds.top).toInt().coerceIn(0, height)
    val y1 = ceil(bounds.bottom).toInt().coerceIn(0, height)
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            if (box.contains(Offset(x + 0.5f, y + 0.5f))) action(y * width + x)
        }
    }
}

/**
 * Measures [scene], the layout of [result] over [source].
 *
 * @param floorPx the readable-size floor the scene was built with.
 * @param layoutMs how long building it took, passed through to the figures.
 */
internal fun measurePage(
    page: String,
    style: String,
    source: ImageBitmap,
    result: ScanResult,
    scene: OverlayScene,
    floorPx: Float,
    layoutMs: Double,
): MeasuredPage {
    val w = source.width
    val h = source.height
    val before = IntArray(w * h).also { source.readPixels(it) }
    val erasedOnly = OverlayScene(scene.source, w, h, scene.erases, emptyList())
    val erased = IntArray(w * h).also { pixels ->
        drawBitmap(w, h) {
            drawImage(source)
            drawOverlayScene(erasedOnly)
        }.readPixels(pixels)
    }
    val picture = drawBitmap(w, h) {
        drawImage(source)
        drawOverlayScene(scene)
    }
    val after = IntArray(w * h).also { picture.readPixels(it) }
    // The finished picture, washed out, with every defect in a colour of its own.
    val diagnostic = IntArray(w * h) { i ->
        val p = after[i]
        val r = (((p shr 16) and 0xFF) + 255) / 2
        val g = (((p shr 8) and 0xFF) + 255) / 2
        val b = ((p and 0xFF) + 255) / 2
        ARGB_BLACK or (r shl 16) or (g shl 8) or b
    }

    // ---- the text ------------------------------------------------------------
    val byParagraph = scene.texts.groupBy { it.paragraph }
    val erasedDetail = detailMap(erased, w, h)
    // How many detail pixels lie above and to the left of each pixel: any window is then four lookups.
    val detailSum = IntArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        var row = 0
        for (x in 0 until w) {
            if (erasedDetail[y * w + x]) row++
            detailSum[(y + 1) * (w + 1) + x + 1] = detailSum[y * (w + 1) + x + 1] + row
        }
    }
    fun detailWithin(x: Int, y: Int, reach: Int): Boolean {
        val x0 = max(0, x - reach)
        val y0 = max(0, y - reach)
        val x1 = min(w, x + reach + 1)
        val y1 = min(h, y + reach + 1)
        return detailSum[y1 * (w + 1) + x1] - detailSum[y0 * (w + 1) + x1] - detailSum[y1 * (w + 1) + x0] + detailSum[y0 * (w + 1) + x0] > 0
    }
    val owner = IntArray(w * h) { -1 }
    var inkPx = 0
    var onDetail = 0
    var crowdedPx = 0
    var touching = 0
    var outside = 0
    var overlapPx = 0
    var minContrast = 21.0
    for ((paragraph, ops) in byParagraph) {
        val fill = inkOf(scene, ops, outlines = false)
        val under = IntArray(fill.inked.count { it })
        val reach = max(2, (median(ops.map { it.fontSize }) * CROWD_REACH).roundToInt())
        val crowdedBefore = onDetail + crowdedPx
        var covered = 0
        fill.forEach { x, y ->
            inkPx++
            if (x < 0 || y < 0 || x >= w || y >= h) {
                outside++
            } else {
                val i = y * w + x
                under[covered++] = erased[i]
                if (erasedDetail[i]) {
                    onDetail++
                    diagnostic[i] = 0xFFFF0000.toInt()
                } else if (detailWithin(x, y, reach)) {
                    crowdedPx++
                    diagnostic[i] = 0xFFB00060.toInt()
                }
            }
        }
        if (onDetail + crowdedPx - crowdedBefore >= TOUCH_PX) touching++
        // What the text stands on is what is commonest under its ink.
        modeColor(under, covered)?.let { background ->
            val text = ops.first().fill.layoutInput.style.color.toArgb()
            minContrast = min(minContrast, contrastRatio(text, background))
        }
        inkOf(scene, ops, outlines = true).forEach { x, y ->
            if (x < 0 || y < 0 || x >= w || y >= h) return@forEach
            val i = y * w + x
            when (owner[i]) {
                -1 -> owner[i] = paragraph
                paragraph -> Unit
                else -> {
                    overlapPx++
                    diagnostic[i] = 0xFFFFD000.toInt()
                }
            }
        }
    }

    val lineJump = byParagraph.values.maxOfOrNull { ops -> ops.maxOf { it.fontSize } / ops.minOf { it.fontSize } } ?: 1f

    class Sized(val font: Float, val source: Float)
    val sized = byParagraph.mapNotNull { (paragraph, ops) ->
        val lines = result.paragraphs[paragraph].lines.mapNotNull { it.box?.toPixels(w.toFloat(), h.toFloat()) }
        if (lines.isEmpty()) null else Sized(median(ops.map { it.fontSize }), median(lines.map { min(it.width, it.height) }))
    }
    var peerJump = 1f
    var pairs = 0
    var uneven = 0
    for (i in sized.indices) {
        for (j in i + 1 until sized.size) {
            val a = sized[i]
            val b = sized[j]
            if (max(a.source, b.source) > min(a.source, b.source) * PEER_SIZE) continue
            pairs++
            val ratio = max(a.font, b.font) / min(a.font, b.font)
            peerJump = max(peerJump, ratio)
            if (ratio > PEER_UNEVEN) uneven++
        }
    }

    val characters = scene.texts.sumOf { it.fill.layoutInput.text.length }
    val small = scene.texts.filter { it.fontSize < floorPx - 0.25f }.sumOf { it.fill.layoutInput.text.length }
    val readable = scene.texts.sumOf { op ->
        op.fill.layoutInput.text.length * (if (floorPx > 0f) min(1f, op.fontSize / floorPx) else 1f).toDouble()
    }
    val bySize = scene.texts.sortedBy { it.fontSize }
    var seen = 0
    val medianFont = bySize.firstOrNull { op ->
        seen += op.fill.layoutInput.text.length
        seen * 2 >= characters
    }?.fontSize ?: 0f

    var edgeSpill = 0f
    for ((index, ops) in byParagraph) {
        val paragraph = result.paragraphs[index]
        val translation = paragraph.translation ?: continue
        val box = paragraph.box?.toPixels(w.toFloat(), h.toFloat()) ?: continue
        val lines = paragraph.lines.mapNotNull { it.box?.toPixels(w.toFloat(), h.toFloat()) }
        // Only a start-aligned paragraph set line by line has an edge its lines share.
        if (translation.alignment != TextAlignment.Start || lines.size < 2 || ops.size < 2) continue
        if (ops.any { it.fill.lineCount != 1 || it.quarterTurn }) continue
        val rtl = translation.writingDirection == WritingDirection.RightToLeft || isRtlLanguage(translation.targetLanguage)
        val lineHeight = median(lines.map { it.height })
        if (lineHeight <= 0f) continue
        for (op in ops) {
            val start = Offset(op.topLeft.x + if (rtl) op.fill.size.width.toFloat() else 0f, op.topLeft.y + op.fill.size.height / 2f)
            val along = box.toLocal(op.toImage(start)).x
            val spill = if (rtl) along - box.width / 2 else -box.width / 2 - along
            edgeSpill = max(edgeSpill, spill / lineHeight)
        }
    }

    // ---- the erasing ---------------------------------------------------------
    val glyph = BooleanArray(w * h)
    val nearLine = BooleanArray(w * h)
    var glyphPx = 0
    val scratch = ArrayList<Int>()
    for (paragraph in result.paragraphs) {
        if (paragraph.translation == null) continue
        for (line in paragraph.lines) {
            val box = line.box?.toPixels(w.toFloat(), h.toFloat())?.takeIf { it.width >= 1f && it.height >= 1f } ?: continue
            scratch.clear()
            forEachPixelIn(box, w, h) { scratch += it }
            val background = modeColor(IntArray(scratch.size) { before[scratch[it]] }) ?: continue
            for (i in scratch) {
                if (!glyph[i] && channelGap(before[i], background) > GLYPH_GAP) {
                    glyph[i] = true
                    glyphPx++
                }
            }
            val pad = 0.25f * min(box.width, box.height) + 2f
            forEachPixelIn(box.inflate(pad, pad), w, h) { nearLine[it] = true }
        }
    }
    var left = 0
    for (i in glyph.indices) {
        if (glyph[i] && channelGap(erased[i], before[i]) < UNCHANGED_GAP) {
            left++
            diagnostic[i] = 0xFFFF00FF.toInt()
        }
    }

    val beforeDetail = detailMap(before, w, h)
    var damaged = 0
    for (i in before.indices) {
        if (!nearLine[i] && beforeDetail[i] && !erasedDetail[i] && channelGap(erased[i], before[i]) > DAMAGE_GAP) {
            damaged++
            diagnostic[i] = 0xFFFF8000.toInt()
        }
    }

    var seamPx = 0
    val changed = BooleanArray(w * h) { channelGap(erased[it], before[it]) > CHANGED_GAP }
    fun seamBetween(i: Int, j: Int): Boolean =
        !changed[j] && channelGap(before[i], before[j]) < UNCHANGED_GAP && channelGap(erased[i], erased[j]) > SEAM_STEP
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            if (!changed[i]) continue
            val seam = (x > 0 && seamBetween(i, i - 1)) || (x < w - 1 && seamBetween(i, i + 1)) ||
                (y > 0 && seamBetween(i, i - w)) || (y < h - 1 && seamBetween(i, i + w))
            if (seam) {
                seamPx++
                diagnostic[i] = 0xFF00B0FF.toInt()
            }
        }
    }

    val perThousandGlyphs = if (glyphPx > 0) 1000.0 / glyphPx else 0.0
    val quality = PageQuality(
        page = page,
        style = style,
        floorPx = floorPx,
        paragraphs = byParagraph.size,
        textOps = scene.texts.size,
        layoutMs = layoutMs,
        inkPx = inkPx,
        overlapPx = overlapPx,
        inkOverDetail = if (inkPx > 0) onDetail.toDouble() / inkPx else 0.0,
        crowded = if (inkPx > 0) (onDetail + crowdedPx).toDouble() / inkPx else 0.0,
        touching = touching,
        clipped = if (inkPx > 0) outside.toDouble() / inkPx else 0.0,
        belowFloor = if (characters > 0) small.toDouble() / characters else 0.0,
        legibility = if (characters > 0) readable / characters else 1.0,
        medianFont = medianFont,
        lineJump = lineJump.toDouble(),
        peerJump = peerJump.toDouble(),
        peerUneven = if (pairs > 0) uneven.toDouble() / pairs else 0.0,
        edgeSpill = edgeSpill.toDouble(),
        minContrast = if (byParagraph.isEmpty()) 0.0 else minContrast,
        glyphPx = glyphPx,
        residual = if (glyphPx > 0) left.toDouble() / glyphPx else 0.0,
        artDamage = damaged * perThousandGlyphs,
        seam = seamPx * perThousandGlyphs,
    )
    return MeasuredPage(quality, picture, diagnostic)
}

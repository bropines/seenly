package io.github.bropines.lens.app.ui.viewer

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.protocol.Box as LensBox

/** A detected object to tap: Lens's id and its normalised box. */
data class Tappable(val id: String, val box: LensBox)

/**
 * "Circle to search" over the image: draw around something with a finger, or
 * tap one of the pulsing dots Lens put on the objects it found. Reports the
 * area as a rectangle normalised to the image.
 */
@Composable
fun SearchLayer(
    image: ImageBitmap,
    objects: List<Tappable>,
    onRegion: (Rect) -> Unit,
    onObject: (Tappable) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    var drawing by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "gleam").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "gleam",
    )

    // Where the fitted image sits inside the layer.
    val fit = remember(size, image) { fitRect(image.width, image.height, size) }
    fun toImage(p: Offset) = Offset(((p.x - fit.left) / fit.width).coerceIn(0f, 1f), ((p.y - fit.top) / fit.height).coerceIn(0f, 1f))

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(objects, fit) {
                detectTapGestures { tap ->
                    val p = toImage(tap)
                    objects.minByOrNull { (it.box.centerX - p.x).let { dx -> dx * dx } + (it.box.centerY - p.y).let { dy -> dy * dy } }
                        ?.takeIf { p.x in it.box.left..it.box.right && p.y in it.box.top..it.box.bottom }
                        ?.let(onObject)
                }
            }
            .pointerInput(fit) {
                detectDragGestures(
                    onDragStart = { stroke.clear(); stroke += it; drawing = true },
                    onDrag = { change, _ -> stroke += change.position },
                    onDragEnd = {
                        drawing = false
                        if (stroke.size > 3 && !fit.isEmpty) {
                            val pts = stroke.map(::toImage)
                            val region = Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
                            // A stray flick is not a selection.
                            if (region.width > 0.02f && region.height > 0.02f) onRegion(region)
                        }
                    },
                    onDragCancel = { drawing = false; stroke.clear() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image,
                dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                dstSize = IntSize(fit.width.toInt(), fit.height.toInt()),
            )
            // Dim the picture slightly so the dots and the stroke read.
            drawRect(Color.Black.copy(alpha = 0.25f), topLeft = fit.topLeft, size = fit.size)
            if (!drawing) {
                objects.forEach { obj ->
                    val c = Offset(fit.left + obj.box.centerX * fit.width, fit.top + obj.box.centerY * fit.height)
                    drawCircle(Color.White.copy(alpha = 0.35f * pulse), radius = 18.dp.toPx() * pulse, center = c)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = c)
                    drawCircle(accent, radius = 6.dp.toPx(), center = c)
                }
            }
            if (stroke.size > 1) {
                val path = Path().apply {
                    moveTo(stroke.first().x, stroke.first().y)
                    stroke.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The ContentScale.Fit rectangle of a w×h image inside [area]. */
internal fun fitRect(w: Int, h: Int, area: IntSize): Rect {
    if (w <= 0 || h <= 0 || area.width <= 0 || area.height <= 0) return Rect.Zero
    val scale = minOf(area.width.toFloat() / w, area.height.toFloat() / h)
    val size = Size(w * scale, h * scale)
    val left = (area.width - size.width) / 2
    val top = (area.height - size.height) / 2
    return Rect(left, top, left + size.width, top + size.height)
}


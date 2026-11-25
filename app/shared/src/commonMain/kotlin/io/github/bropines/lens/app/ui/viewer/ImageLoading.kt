package io.github.bropines.lens.app.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.bropines.lens.app.platform.quarterTurns
import io.github.bropines.lens.app.platform.rotated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import io.github.vinceglb.filekit.PlatformFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decodes [file] for display. [maxSide] bounds an ordinary picture's longer
 * side, so a 50 MP photo cannot exhaust memory; a strip many times longer
 * than wide keeps more of its length (see [displayBound]).
 */
expect suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap?

/**
 * The side of the square a [width] x [height] picture is fitted into when it
 * is decoded for display.
 *
 * An ordinary picture is bounded by [maxSide] on its longer side. Bounding a
 * webtoon strip or a long screenshot that way leaves a sliver a couple of
 * hundred pixels wide, unreadable once it is zoomed to the screen's width. A
 * long picture may therefore keep as many pixels as a 2:3 page bounded by
 * [maxSide] has: it costs the same memory and stays sharp. [sideLimit] is
 * the longest side the platform can draw.
 */
internal fun displayBound(width: Int, height: Int, maxSide: Int, sideLimit: Int): Int {
    val long = max(width, height)
    if (width <= 0 || height <= 0 || long <= maxSide) return maxSide
    val bySide = maxSide.toDouble() / long
    val budget = maxSide.toDouble() * (maxSide * 2 / 3)
    val byArea = sqrt(budget / (width.toDouble() * height))
    val scale = min(1.0, max(bySide, byArea))
    return (long * scale).roundToInt().coerceIn(maxSide, max(maxSide, sideLimit))
}

sealed interface ImageState {
    data object Loading : ImageState
    /** [bitmap] as shown: the file's pixels turned [rotation] quarter turns clockwise. */
    data class Ready(val bitmap: ImageBitmap, val rotation: Int = 0) : ImageState
    data object Failed : ImageState
}

/**
 * [file] decoded for display and turned [rotation] quarter turns clockwise.
 * The file is decoded once; a new turn only rotates the decoded pixels.
 */
@Composable
fun rememberImage(file: PlatformFile, rotation: Int = 0, maxSide: Int = 3072): State<ImageState> {
    val context = LocalPlatformContext.current
    val decoded by produceState<ImageState>(ImageState.Loading, file) {
        value = loadImageBitmap(context, file, maxSide)?.let(ImageState::Ready) ?: ImageState.Failed
    }
    val turns = quarterTurns(rotation)
    return produceState(decoded, decoded, turns) {
        val base = decoded
        value = if (base is ImageState.Ready && turns != 0) {
            ImageState.Ready(withContext(Dispatchers.Default) { base.bitmap.rotated(turns) }, turns)
        } else {
            base
        }
    }
}

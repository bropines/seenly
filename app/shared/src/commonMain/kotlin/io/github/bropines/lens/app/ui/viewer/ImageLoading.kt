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

/** Decodes [file] for display, bounded to [maxSide] px so a 50 MP photo cannot exhaust memory. */
expect suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap?

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

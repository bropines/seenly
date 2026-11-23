package io.github.bropines.lens.app.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import io.github.vinceglb.filekit.PlatformFile

/** Decodes [file] for display, bounded to [maxSide] px so a 50 MP photo cannot exhaust memory. */
expect suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap?

sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
    data object Failed : ImageState
}

@Composable
fun rememberImage(file: PlatformFile, maxSide: Int = 3072): State<ImageState> {
    val context = LocalPlatformContext.current
    return produceState<ImageState>(ImageState.Loading, file) {
        value = loadImageBitmap(context, file, maxSide)?.let(ImageState::Ready) ?: ImageState.Failed
    }
}

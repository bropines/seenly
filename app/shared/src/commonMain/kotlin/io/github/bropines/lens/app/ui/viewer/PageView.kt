package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.overlay.OverlayMode

/** One page of the viewer: the image, with the translation over it once the scan is in. */
@Composable
fun PageView(item: GalleryItem, state: PageState?, mode: OverlayMode, onTap: () -> Unit) {
    val image by rememberImage(item.file)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        when (val loaded = image) {
            ImageState.Loading -> LoadingIndicator()
            ImageState.Failed -> Icon(Icons.Rounded.BrokenImage, contentDescription = "Не удалось открыть", tint = Color.White)
            is ImageState.Ready -> Image(
                bitmap = loaded.bitmap,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

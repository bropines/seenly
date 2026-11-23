package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayState
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.TextSelectionState
import io.github.bropines.lens.overlay.TranslatedImage
import io.github.bropines.lens.overlay.rememberOverlayState
import io.github.bropines.lens.overlay.rememberZoomState

/** What a page has ready for export: the shown bitmap and its decoded overlay. */
class PageRender(val bitmap: ImageBitmap, val overlay: OverlayState?)

/**
 * One page of the viewer: the image with the translation over it once the
 * scan is in, zoomable. Reports what it has decoded through [onRender] so the
 * screen can export the visible page without decoding it twice.
 */
@Composable
fun PageView(
    item: GalleryItem,
    state: PageState?,
    mode: OverlayMode,
    style: OverlayStyle,
    onTap: () -> Unit,
    onParagraphTap: (Int) -> Unit,
    onZoomed: (Boolean) -> Unit,
    onRender: (PageRender?) -> Unit,
    /** Text selection, for the visible page only. */
    selection: TextSelectionState? = null,
) {
    val image by rememberImage(item.file)
    val result = (state as? PageState.Ready)?.result
    // Keep the old translation up while a new language decodes: no blink to the bare image.
    val overlay = rememberOverlayState(result, keepPrevious = true)
    val zoom = rememberZoomState()

    LaunchedEffect(zoom) { snapshotFlow { zoom.isZoomed }.collect(onZoomed) }
    LaunchedEffect(image, overlay) { onRender((image as? ImageState.Ready)?.let { PageRender(it.bitmap, overlay) }) }

    when (val loaded = image) {
        ImageState.Loading -> Centered(onTap) { LoadingIndicator() }
        ImageState.Failed -> Centered(onTap) {
            Icon(Icons.Rounded.BrokenImage, contentDescription = "Не удалось открыть", tint = Color.White)
        }
        is ImageState.Ready -> TranslatedImage(
            image = loaded.bitmap,
            state = overlay,
            mode = mode,
            style = style,
            modifier = Modifier.fillMaxSize(),
            onParagraphTap = onParagraphTap,
            onBackgroundTap = onTap,
            zoomState = zoom,
            selection = selection,
        )
    }
}

@Composable
private fun Centered(onTap: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

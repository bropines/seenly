package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.bropines.lens.app.platform.quarterTurns
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.ui.gallery.cachedThumbnail
import io.github.bropines.lens.app.ui.gallery.shimmer
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayState
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.TextSelectionState
import io.github.bropines.lens.overlay.TranslatedImage
import io.github.bropines.lens.overlay.ZoomState
import io.github.bropines.lens.overlay.rememberOverlayState
import io.github.bropines.lens.overlay.rememberZoomState

/** What a page has ready for export: the shown bitmap and its decoded overlay. */
class PageRender(val bitmap: ImageBitmap, val overlay: OverlayState?)

/** What the screen knows about the page in front, and the hold it has on it. */
@Stable
class PageHandle {
    /** The picture the page in front shows, by [GalleryItem.key]; what follows is about that picture. */
    var owner: String? by mutableStateOf(null)
        internal set

    /** Where the picture sits in the page while nothing moves, in the page's own pixels; empty until it is laid out. */
    var picture: Rect by mutableStateOf(Rect.Zero)
        internal set

    /** Whether the page is a long strip, read by scrolling rather than fitted to the screen. */
    var strip: Boolean by mutableStateOf(false)
        internal set

    /** How far a strip is scrolled, in pixels; 0 for a page that fits. */
    var scrollY: Float by mutableFloatStateOf(0f)
        internal set

    internal var zoom: ZoomState? = null

    /** Whether the picture is magnified. */
    val isZoomed: Boolean get() = zoom?.isZoomed == true

    /** Back to the fitted picture at once, e.g. before it flies away. */
    fun resetZoom() {
        zoom?.takeIf { it.isZoomed }?.snapTo(1f)
    }
}

/** A picture this many times longer than wide is a strip (a webtoon, a long screenshot), not a page. */
private const val STRIP_ASPECT = 2.8f

/** ...and it is read as one only where fitting it whole would leave it this much narrower than the screen. */
private const val STRIP_NARROWING = 1.5f

/**
 * Whether a [width] x [height] picture is read by scrolling in a page of
 * [page]: it is long in itself, and fitted whole it would be a narrow band.
 */
internal fun isStrip(width: Int, height: Int, page: IntSize): Boolean {
    if (width <= 0 || height <= 0 || page.width <= 0 || page.height <= 0) return false
    val aspect = height.toFloat() / width
    return aspect >= STRIP_ASPECT && aspect >= STRIP_NARROWING * page.height / page.width
}

/**
 * One page of the viewer: the image with the translation over it once the
 * scan is in, zoomable. Reports what it has decoded through [onRender] so the
 * screen can export the visible page without decoding it twice.
 *
 * Until the page itself is decoded, the thumbnail a grid or the strip made
 * of it stands in, in the same place, so the picture is there from the first
 * frame. A long strip is laid out at the screen's width and scrolled.
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
    /** Clockwise quarter turns: the picture is shown, and was scanned, turned this far. */
    rotation: Int = 0,
    /** Given to the page in front: it tells the screen where its picture is. */
    handle: PageHandle? = null,
    /** Pulling down and holding, for the page in front. */
    gestures: PageGestures? = null,
    /** Pages are flying past (the strip is being scrubbed): show the thumbnail and decode nothing. */
    light: Boolean = false,
    /** The page is being read right now: a band of light runs down it. */
    scanning: Boolean = false,
) {
    val context = LocalPlatformContext.current
    // A page that was decoding before pages began to fly past keeps what it has; one born in flight waits.
    var decode by remember { mutableStateOf(!light) }
    LaunchedEffect(light) { if (!light) decode = true }
    val image: ImageState = if (decode) rememberImage(item.file, rotation).value else ImageState.Loading
    val result = (state as? PageState.Ready)?.result
    // A new turn starts the overlay and zoom afresh: the old boxes fit the old turn only.
    // Within a turn, the old translation stays up while a new language decodes.
    val decoded = key(rotation) { rememberOverlayState(result, keepPrevious = true) }
    val zoom = key(rotation) { rememberZoomState() }
    val ready = image as? ImageState.Ready
    // While the turned bitmap is still being made, no overlay over the old one.
    val overlay = decoded.takeIf { ready?.rotation == quarterTurns(rotation) }
    // The small picture already in memory, upright only: it knows nothing of a turn.
    val stand = remember(item.key, rotation) { if (quarterTurns(rotation) == 0) cachedThumbnail(context, item.key) else null }

    var page by remember { mutableStateOf(IntSize.Zero) }
    val strip = ready != null && isStrip(ready.bitmap.width, ready.bitmap.height, page)
    val scroll = rememberScrollState()

    LaunchedEffect(zoom) { snapshotFlow { zoom.isZoomed }.collect(onZoomed) }
    LaunchedEffect(image, overlay) { onRender(ready?.let { PageRender(it.bitmap, overlay) }) }
    // A page that leaves must not leave its bitmap behind in the screen's map.
    DisposableEffect(Unit) { onDispose { onRender(null) } }

    if (handle != null) {
        val whole = Rect(0f, 0f, page.width.toFloat(), page.height.toFloat())
        val picture = when {
            page == IntSize.Zero -> Rect.Zero
            strip -> whole
            ready != null -> fitRect(ready.bitmap.width, ready.bitmap.height, page)
            stand != null -> fitRect(stand.width, stand.height, page)
            else -> whole
        }
        SideEffect {
            handle.owner = item.key
            handle.picture = picture
            handle.strip = strip
            handle.zoom = zoom
        }
        LaunchedEffect(handle, strip) {
            if (strip) snapshotFlow { scroll.value }.collect { handle.scrollY = it.toFloat() } else handle.scrollY = 0f
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { page = it }
            .then(
                if (gestures != null) {
                    Modifier.pointerInput(gestures) { detectDismissDrag(gestures) }.pointerInput(gestures) { detectHold(gestures) }
                } else {
                    Modifier
                },
            ),
    ) {
        when {
            // Not before the page is measured: a strip is laid out differently, and laying out twice is work twice.
            ready != null && page != IntSize.Zero -> {
                if (strip) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        TranslatedImage(
                            image = ready.bitmap,
                            state = overlay,
                            mode = mode,
                            style = style,
                            // A layer of its own: scrolling moves it, and nothing is drawn again.
                            modifier = Modifier.fillMaxWidth().graphicsLayer(),
                            onParagraphTap = onParagraphTap,
                            onBackgroundTap = onTap,
                            zoomState = zoom,
                            selection = selection,
                        )
                    }
                } else {
                    TranslatedImage(
                        image = ready.bitmap,
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
            image == ImageState.Failed -> Centered(onTap) {
                Icon(Icons.Rounded.BrokenImage, contentDescription = stringResource(Res.string.page_open_failed), tint = Color.White)
            }
            stand != null -> Image(
                painter = remember(stand) { stand.asPainter(context) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            )
            else -> Centered(onTap) {
                Box(Modifier.fillMaxWidth(0.62f).aspectRatio(0.7f).clip(MaterialTheme.shapes.large).shimmer(Skeleton, SkeletonLight))
            }
        }
        if (scanning && page != IntSize.Zero && (ready != null || stand != null)) {
            val area = when {
                strip || ready == null && stand == null -> Rect(0f, 0f, page.width.toFloat(), page.height.toFloat())
                ready != null -> fitRect(ready.bitmap.width, ready.bitmap.height, page)
                else -> fitRect(stand!!.width, stand.height, page)
            }
            ScanSweep(area)
        }
    }
}

private val Skeleton = Color(0xFF1C1C1E)
private val SkeletonLight = Color(0xFF3A3A3F)

/**
 * A band of light running down [area] while the page is being read: the
 * picture is there, the text on it is on its way.
 */
@Composable
private fun ScanSweep(area: Rect) {
    val phase by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "scan",
    )
    val tint = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (area.isEmpty) return@drawBehind
            val band = area.height * 0.22f
            val top = area.top - band + (area.height + band) * phase
            val from = top.coerceAtLeast(area.top)
            val until = (top + band).coerceAtMost(area.bottom)
            if (until <= from) return@drawBehind
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.85f to tint.copy(alpha = 0.28f),
                    1f to Color.White.copy(alpha = 0.5f),
                    startY = top,
                    endY = top + band,
                ),
                topLeft = Offset(area.left, from),
                size = Size(area.width, until - from),
            )
        },
    )
}

@Composable
private fun Centered(onTap: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

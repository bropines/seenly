package io.github.bropines.lens.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import io.github.vinceglb.filekit.PlatformFile

/**
 * The size every thumbnail is decoded at, whatever cell shows it: the picture
 * covers a square of this side. One decode then serves the strip, the grids,
 * and the picture's flight into the viewer.
 */
const val THUMBNAIL_PX = 320

/** The name a picture's thumbnail has in the image cache. */
fun thumbnailCacheKey(key: String): String = "thumb:$key"

/** The one request all thumbnails of [key] are made with, so they share a cache entry. */
fun thumbnailRequest(context: PlatformContext, file: PlatformFile, key: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(thumbnailCacheKey(key))
        .size(THUMBNAIL_PX)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .crossfade(true)
        .build()

/** [key]'s thumbnail if one is decoded and still in memory: a stand-in while the page itself is read. */
fun cachedThumbnail(context: PlatformContext, key: String): Image? {
    val cache = SingletonImageLoader.get(context).memoryCache ?: return null
    val name = thumbnailCacheKey(key)
    return cache[MemoryCache.Key(name)]?.image
        ?: cache.keys.firstOrNull { it.key == name }?.let { cache[it]?.image }
}

/** What is known of a page without opening it. */
enum class PageBadge {
    /** A translation is stored or was just made. */
    Translated,

    /** The last try to read it failed. */
    Failed,
}

/**
 * A picture's thumbnail, cropped to fill its cell: a shimmer until it is
 * decoded, a broken-image mark when it cannot be.
 */
@Composable
fun Thumbnail(
    file: PlatformFile,
    key: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentDescription: String? = null,
) {
    val context = LocalPlatformContext.current
    val request = remember(key, file) { thumbnailRequest(context, file, key) }
    // Known at once when the cache has it: no shimmer for a frame over a picture that is there.
    var state by remember(key) { mutableStateOf(if (cachedThumbnail(context, key) != null) Loaded.Yes else Loaded.NotYet) }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (state == Loaded.NotYet) Box(Modifier.fillMaxSize().shimmer())
        if (state == Loaded.Failed) {
            Icon(
                Icons.Rounded.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onState = {
                state = when (it) {
                    is AsyncImagePainter.State.Success -> Loaded.Yes
                    is AsyncImagePainter.State.Error -> Loaded.Failed
                    else -> state
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private enum class Loaded { NotYet, Yes, Failed }

/** The mark a thumbnail carries for [badge]: a tick for a translated page, an exclamation for a failed one. */
@Composable
fun BadgeMark(badge: PageBadge, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val failed = badge == PageBadge.Failed
    Box(
        modifier
            .size(size)
            .background(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (failed) Icons.Rounded.PriorityHigh else Icons.Rounded.Check,
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.68f),
        )
    }
}

package io.github.bropines.lens.app.ui.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import javax.imageio.ImageIO

actual suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap? {
    val bound = withContext(Dispatchers.IO) { pictureSize(file) }
        ?.let { (width, height) -> displayBound(width, height, maxSide, LONG_SIDE) }
        ?: maxSide
    val request = ImageRequest.Builder(context)
        .data(file)
        .size(bound)
        .maxBitmapSize(Size(bound, bound))
        .precision(Precision.INEXACT)
        // A page is large and the pager keeps its neighbours itself: the cache is the thumbnails'.
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asComposeImageBitmap()
}

/**
 * The picture's size, or null when it cannot be read: from the header alone
 * where ImageIO knows the format, else (WebP and the like) by letting Skia
 * look at the file.
 */
private fun pictureSize(file: PlatformFile): Pair<Int, Int>? = runCatching {
    ImageIO.createImageInputStream(file.file)?.use { stream ->
        ImageIO.getImageReaders(stream).asSequence().firstOrNull()?.let { reader ->
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    } ?: Data.makeFromBytes(file.file.readBytes()).use { data -> Codec.makeFromData(data).use { it.width to it.height } }
}.getOrNull()

private const val LONG_SIDE = 16_384

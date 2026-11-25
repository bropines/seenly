package io.github.bropines.lens.app.ui.viewer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap? {
    // The renderer before Android 10 could not draw a bitmap longer than a texture; later ones cut it up themselves.
    val sideLimit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) LONG_SIDE else TEXTURE_SIDE
    val bound = withContext(Dispatchers.IO) { pictureSize(context, file) }
        ?.let { (width, height) -> displayBound(width, height, maxSide, sideLimit) }
        ?: maxSide
    val request = ImageRequest.Builder(context)
        .data(file)
        .size(bound)
        .maxBitmapSize(Size(bound, bound))
        .precision(Precision.INEXACT)
        // Software pixels: the overlay exporter reads them back.
        .allowHardware(false)
        // A page is large and the pager keeps its neighbours itself: the cache is the thumbnails'.
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asImageBitmap()
}

/** The picture's size from its header alone, or null when that cannot be read. */
private fun pictureSize(context: Context, file: PlatformFile): Pair<Int, Int>? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // As a stream in both cases, the way the decoder itself reads the file.
    val stream = when (val source = file.androidFile) {
        is AndroidFile.FileWrapper -> source.file.inputStream()
        is AndroidFile.UriWrapper -> context.contentResolver.openInputStream(source.uri)
    }
    stream?.use { BitmapFactory.decodeStream(it, null, options) }
    (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
}.getOrNull()

private const val LONG_SIDE = 16_384
private const val TEXTURE_SIDE = 4_096

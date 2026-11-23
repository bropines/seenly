package io.github.bropines.lens.app.ui.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import io.github.vinceglb.filekit.PlatformFile

actual suspend fun loadImageBitmap(context: PlatformContext, file: PlatformFile, maxSide: Int): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(file)
        .size(maxSide)
        .precision(Precision.INEXACT)
        // Software pixels: the overlay exporter reads them back.
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asImageBitmap()
}

package io.github.bropines.lens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Visual search through Google's own Lens, in the Google app, instead of
 * Seenly's request: the picture is shared to it the way the system share sheet
 * would. A picture Lens may not take (HEIC, AVIF, GIF and the like, a huge
 * file) or only part of one goes as a JPEG Seenly makes first. Without the
 * app, or when it refuses, the caller searches itself.
 */
internal class GoogleLensHandoff(private val context: Context) {
    /** Whether a Google app that takes shared images is installed. */
    fun available(): Boolean = target() != null

    /**
     * Shares [image] to Google Lens, cut to [region] (left, top, right, bottom
     * as fractions of the picture) when given. [from] is the activity in front,
     * if any. False when there is no app to take it or the picture can't be read.
     */
    fun search(image: ByteArray, region: FloatArray?, from: Context?): Boolean {
        val target = target() ?: return false
        val (bytes, mime) = prepare(image, region) ?: return false
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith(PREFIX) }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val file = File(dir, PREFIX + System.currentTimeMillis() + if (mime == "image/png") ".png" else ".jpg").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .setClassName(target.activityInfo.packageName, target.activityInfo.name)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return runCatching {
            if (from != null) from.startActivity(send) else context.startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Google Lens did not take the picture", it) }.isSuccess
    }

    /**
     * The activity that takes a shared image in the Google app, preferring the one for
     * Lens ("Search image") over others the app may list for the same intent.
     */
    private fun target(): ResolveInfo? {
        val probe = Intent(Intent.ACTION_SEND).setType("image/jpeg")
        return PACKAGES.firstNotNullOfOrNull { pkg ->
            val found = query(Intent(probe).setPackage(pkg))
            found.firstOrNull { "lens" in it.activityInfo.name.lowercase() } ?: found.firstOrNull()
        }
    }

    private fun query(intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
    }.getOrDefault(emptyList())

    /** The bytes to send and their type: the picture as it is when Lens takes it, else a JPEG of it or of [region]. */
    private fun prepare(image: ByteArray, region: FloatArray?): Pair<ByteArray, String>? {
        if (region == null && image.size <= AS_IS_BYTES) {
            when {
                image.isJpeg() -> return image to "image/jpeg"
                image.isPng() -> return image to "image/png"
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val picture = region?.let { r ->
            val cut = Rect(
                (r[0] * decoded.width).roundToInt().coerceIn(0, decoded.width - 1),
                (r[1] * decoded.height).roundToInt().coerceIn(0, decoded.height - 1),
                (r[2] * decoded.width).roundToInt().coerceIn(1, decoded.width),
                (r[3] * decoded.height).roundToInt().coerceIn(1, decoded.height),
            )
            if (cut.width() < 8 || cut.height() < 8) decoded else Bitmap.createBitmap(decoded, cut.left, cut.top, cut.width(), cut.height())
        } ?: decoded
        val out = ByteArrayOutputStream()
        picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (picture !== decoded) picture.recycle()
        decoded.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    private fun ByteArray.isJpeg() = size > 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

    private fun ByteArray.isPng() = size > 8 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte()

    private companion object {
        const val TAG = "GoogleLensHandoff"
        const val PREFIX = "lens-"
        /** The Google app, then the old standalone Lens app. */
        val PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.ar.lens")
        /** Larger pictures go as a smaller JPEG: Lens downsizes them anyway. */
        const val AS_IS_BYTES = 12 * 1024 * 1024
        const val MAX_SIDE = 3072
        const val JPEG_QUALITY = 92
    }
}

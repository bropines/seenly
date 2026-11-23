package io.github.bropines.lens.app.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

/** What the shared app needs from the platform it runs on. */
interface PlatformServices {
    /** A private, writable directory for settings and caches. */
    val dataDir: String

    fun copyText(text: String)

    /** Hands [text] to the system share sheet, or the clipboard where there is none. */
    fun shareText(text: String)

    /** Shares an exported image file. */
    fun shareImage(bytes: ByteArray, fileName: String)

    /** Keeps read access to a picked folder across restarts (Android's persistable URI grant). */
    fun persistAccess(folder: PlatformFile) {}
}

/** PNG bytes of the bitmap, for exporting a translated page. */
expect fun androidx.compose.ui.graphics.ImageBitmap.encodePng(): ByteArray

/** The wallpaper-based scheme where the platform has one (Android 12+), else null. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

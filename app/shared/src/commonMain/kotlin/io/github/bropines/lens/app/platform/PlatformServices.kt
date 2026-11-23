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

    /**
     * Opens [url] in the user's own browser. Lens results pages must open
     * there: a WebView or a fresh profile gets Google's captcha.
     */
    fun openUrl(url: String)

    /** Room's builder for the app database (Android needs a Context, the desktop a path). */
    fun databaseBuilder(): androidx.room.RoomDatabase.Builder<io.github.bropines.lens.app.data.history.LensDatabase>

    /** Keeps read access to a picked folder across restarts (Android's persistable URI grant). */
    fun persistAccess(folder: PlatformFile) {}

    /** The floating screen translator, where the platform has one (Android). */
    val screenTranslate: io.github.bropines.lens.app.screen.ScreenTranslate? get() = null
}

/** PNG bytes of the bitmap, for exporting a translated page. */
expect fun androidx.compose.ui.graphics.ImageBitmap.encodePng(): ByteArray

/** The wallpaper-based scheme where the platform has one (Android 12+), else null. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

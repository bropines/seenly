package io.github.bropines.lens.app.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

/** What the shared app needs from the platform it runs on. */
interface PlatformServices {
    /** A private, writable directory for settings and caches. */
    val dataDir: String

    /** The version the user has installed; Android reads it from the package, set by the release tag. */
    val appVersion: String get() = APP_VERSION

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

    /**
     * How this copy installs an update, or null when it must not replace itself:
     * a debug build, a copy a store keeps up to date, the desktop. An update is
     * then only a link to its release page.
     */
    val updateInstaller: io.github.bropines.lens.app.update.UpdateInstaller? get() = null

    /** True when the app was updated over an older copy, false for a fresh install or where that cannot be told. */
    val wasUpdated: Boolean get() = false

    /** Recognition and translation on the device, where the build has them (Android's `google` flavor). */
    val onDevice: io.github.bropines.lens.app.ondevice.OnDeviceMl? get() = null

    /**
     * Where secrets (the Google account's cookies) are kept. Android encrypts
     * them with a key in the Keystore; the default is a private file in [dataDir].
     */
    val secrets: io.github.bropines.lens.app.data.SecretStore get() = io.github.bropines.lens.app.data.FileSecretStore(dataDir)
}

/** The version of a build that cannot tell its own (desktop, previews). */
const val APP_VERSION = "0.1.0"

/** PNG bytes of the bitmap, for exporting a translated page. */
expect fun androidx.compose.ui.graphics.ImageBitmap.encodePng(): ByteArray

/** The wallpaper-based scheme where the platform has one (Android 12+), else null. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

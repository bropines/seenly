package io.github.bropines.lens

import android.app.Activity
import android.content.ComponentName
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import java.lang.ref.WeakReference
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.bropines.lens.app.data.history.LensDatabase
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import io.github.bropines.lens.app.data.SecretStore
import io.github.bropines.lens.app.data.LinkOpening
import io.github.bropines.lens.app.ondevice.OnDeviceMl
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.update.UpdateInstaller
import io.github.bropines.lens.ondevice.createOnDeviceMl
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

class AndroidPlatformServices(private val context: Context) : PlatformServices {
    override val dataDir: String = context.filesDir.absolutePath

    override val appVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ?: super.appVersion
    }

    override fun copyText(text: String) {
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Seenly", text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, AppLocale.string(context, R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareImage(bytes: ByteArray, fileName: String) = sendImage(bytes, fileName, caption = null)

    override fun shareImage(bytes: ByteArray, fileName: String, text: String) = sendImage(bytes, fileName, caption = text)

    private fun sendImage(bytes: ByteArray, fileName: String, caption: String?) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Only the newest few stay: each share leaves a full-size picture behind.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser shows a preview of the picture only when the grant travels as clip data.
        send.clipData = ClipData.newRawUri(fileName, uri)
        if (caption != null) send.putExtra(Intent.EXTRA_TEXT, caption)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun databaseBuilder(): RoomDatabase.Builder<LensDatabase> =
        Room.databaseBuilder<LensDatabase>(context, context.getDatabasePath("lens.db").absolutePath)

    /** The activity in front, for launching tabs over it. Set by MainActivity while resumed. */
    var activity: WeakReference<Activity>? = null

    /**
     * A warmed-up session with the user's browser. Chrome draws a Custom Tab
     * as a partial sheet over the app only when it is launched with one.
     */
    @Volatile private var session: CustomTabsSession? = null

    init {
        CustomTabsClient.getPackageName(context, null)?.let { browser ->
            CustomTabsClient.bindCustomTabsService(
                context,
                browser,
                object : CustomTabsServiceConnection() {
                    override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                        client.warmup(0)
                        session = client.newSession(null)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        session = null
                    }
                },
            )
        }
    }

    /** The last link opened and when, so one asked for twice in a row does not open twice. */
    private var lastOpened: Pair<String, Long>? = null

    override fun openUrl(url: String) {
        val now = android.os.SystemClock.uptimeMillis()
        lastOpened?.let { (last, at) -> if (last == url && now - at < REPEAT_WINDOW_MS) return }
        lastOpened = url to now
        val uri = Uri.parse(url)
        val front = activity?.get()
        val current = session
        if (linkOpening == LinkOpening.Browser) {
            val browser = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { (front ?: context).startActivity(browser) }.isSuccess) return
        }
        // A sheet over the picture, the way Lens shows results: the user's browser
        // (their cookies and consent, so no captcha), resizable, swiped away to return.
        if (linkOpening == LinkOpening.Sheet && front != null && current != null) {
            val launched = runCatching {
                val height = (front.resources.displayMetrics.heightPixels * SHEET_FRACTION).toInt()
                val tab = CustomTabsIntent.Builder(current)
                    .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
                    // 16 dp is the most Custom Tabs accepts.
                    .setToolbarCornerRadiusDp(16)
                    .setShowTitle(true)
                    .build()
                tab.intent.data = uri
                @Suppress("DEPRECATION") // Partial tabs must be started for a result.
                front.startActivityForResult(tab.intent, PARTIAL_TAB_REQUEST)
            }
            if (launched.isSuccess) return
        }
        // No session (or a browser without Custom Tabs): a full tab, then any browser.
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { tab.launchUrl(front ?: context, uri) }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val SHEET_FRACTION = 0.68
        const val REPEAT_WINDOW_MS = 3_000L
        const val PARTIAL_TAB_REQUEST = 7101
    }

    override val screenTranslate = io.github.bropines.lens.screen.AndroidScreenTranslate(context) { activity?.get() }
    override val hasShareTargets: Boolean get() = true

    override val canChooseLinkOpening: Boolean get() = true

    /** Where links open; kept in step with the setting by LensApplication. */
    @Volatile
    var linkOpening: LinkOpening = LinkOpening.Sheet

    override fun returnToCaller() {
        activity?.get()?.moveTaskToBack(true)
    }

    override val updateInstaller: UpdateInstaller? = AndroidUpdateInstaller.forThisCopy(context)

    /** An update leaves the first install time behind; a fresh install has the two equal. */
    override val wasUpdated: Boolean by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { it.lastUpdateTime > it.firstInstallTime } }
            .getOrDefault(false)
    }

    /** ML Kit in the google flavor, nothing in foss: each flavor's source set has its own [createOnDeviceMl]. */
    override val onDevice: OnDeviceMl? = createOnDeviceMl(context)

    override val secrets: SecretStore = KeystoreSecretStore(context)

    override fun persistAccess(folder: PlatformFile) {
        val uri = (folder.androidFile as? AndroidFile.UriWrapper)?.uri ?: return
        // Without this the "continue last folder" grant dies with the process.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

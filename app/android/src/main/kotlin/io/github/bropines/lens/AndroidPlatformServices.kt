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
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

class AndroidPlatformServices(private val context: Context) : PlatformServices {
    override val dataDir: String = context.filesDir.absolutePath

    override fun copyText(text: String) {
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Lens", text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareImage(bytes: ByteArray, fileName: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    override fun openUrl(url: String) {
        val uri = Uri.parse(url)
        val front = activity?.get()
        val current = session
        // A sheet over the picture, the way Lens shows results: the user's browser
        // (their cookies and consent, so no captcha), resizable, swiped away to return.
        if (front != null && current != null) {
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
        const val PARTIAL_TAB_REQUEST = 7101
    }

    override fun persistAccess(folder: PlatformFile) {
        val uri = (folder.androidFile as? AndroidFile.UriWrapper)?.uri ?: return
        // Without this the "continue last folder" grant dies with the process.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

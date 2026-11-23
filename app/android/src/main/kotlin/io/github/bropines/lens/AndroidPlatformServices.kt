package io.github.bropines.lens

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

    override fun openUrl(url: String) {
        // A Custom Tab runs in the user's browser, with their cookies and consent state.
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { tab.launchUrl(context, Uri.parse(url)) }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun persistAccess(folder: PlatformFile) {
        val uri = (folder.androidFile as? AndroidFile.UriWrapper)?.uri ?: return
        // Without this the "continue last folder" grant dies with the process.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

package io.github.bropines.lens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.bropines.lens.app.update.UpdateInstaller
import java.io.File

/**
 * Installs a downloaded release APK through the system's installer: the user
 * confirms every install, and must first have allowed Seenly to install apps.
 * Android itself refuses an APK signed with another key or older than what is
 * installed, so a wrong file cannot replace the app.
 */
class AndroidUpdateInstaller private constructor(private val context: Context) : UpdateInstaller {
    override val flavor: String = BuildConfig.FLAVOR

    override val downloadDir: String = File(context.cacheDir, "updates").absolutePath

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermission() {
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun install(apkPath: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(apkPath))
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
    }

    companion object {
        /** Clients that deliver updates themselves: F-Droid and its relatives, and Play. */
        private val stores = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.fdroid.privileged",
            "org.fdroid.basic",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "com.aurora.adroid",
            "com.android.vending",
        )

        /**
         * The installer for this copy, or null when it must not update itself
         * from GitHub: a debug build has another app id, so a release APK
         * would land beside it, and a store's copy is the store's to update.
         */
        fun forThisCopy(context: Context): AndroidUpdateInstaller? =
            if (BuildConfig.DEBUG || installerOf(context) in stores) null else AndroidUpdateInstaller(context.applicationContext)

        /** The package that installed this app, or null for adb and file managers. */
        private fun installerOf(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
    }
}

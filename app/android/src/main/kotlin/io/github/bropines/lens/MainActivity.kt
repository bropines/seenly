package io.github.bropines.lens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.bropines.lens.app.Incoming
import io.github.bropines.lens.app.LensApp
import androidx.core.content.pm.ShortcutManagerCompat
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class MainActivity : ComponentActivity() {
    /** Images shared or opened into the app. Replays one, so a share that starts the app is not lost before composition. */
    private val incoming = MutableSharedFlow<Incoming>(replay = 1, extraBufferCapacity = 4)

    // Before Android 13 the UI language reaches the activity through its context (see AppLocale).
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        (application as LensApplication).followUiLanguage()
        // After a configuration change the intent was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent { LensApp((application as LensApplication).graph, incoming) }
    }

    // Started, not resumed: a browser sheet over the viewer only pauses it, and taps on the
    // picture behind the sheet must still open their links over this activity.
    override fun onStart() {
        super.onStart()
        services.activity = java.lang.ref.WeakReference(this)
    }

    override fun onStop() {
        if (services.activity?.get() === this) services.activity = null
        super.onStop()
    }

    private val services get() = (application as LensApplication).services

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // Reopened from Recents, the activity gets the intent that first started it: that picture is done.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            ACTION_ASSIST_CAPTURE -> {
                // A screenshot from the assistant session, already in our cache dir.
                intent.getStringExtra(EXTRA_CAPTURE_PATH)?.let { incoming.tryEmit(Incoming(listOf(PlatformFile(File(it))))) }
                return
            }
            else -> emptyList()
        }
        // Shared through one of Seenly's own entries in the share sheet: open in that one's mode.
        val mode = ShareShortcuts.modeOf(intent?.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        if (uris.isNotEmpty()) incoming.tryEmit(Incoming(uris.map { PlatformFile(it) }, mode))
    }

    companion object {
        const val ACTION_ASSIST_CAPTURE = "io.github.bropines.lens.ASSIST_CAPTURE"
        const val EXTRA_CAPTURE_PATH = "capture_path"
    }
}

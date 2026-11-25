package io.github.bropines.lens.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.bropines.lens.AppLocale
import io.github.bropines.lens.R
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Walks the user through what the screen translator needs, in order: drawing
 * over other apps (a Settings page), notifications (so the "Stop" notification
 * is visible; refusing is fine), and the system's screen-capture consent, which
 * Android 14+ asks for anew every session. Then it starts the service and goes
 * away. It draws nothing of its own.
 */
class ScreenPermissionActivity : ComponentActivity() {
    private var overlayAsked = false
    private var notificationsAsked = false

    /** Sent to the overlay Settings page and not back with an answer yet. */
    private var awaitingOverlay = false
    private var leftForSettings = false

    // HyperOS/MIUI open that page in a task of their own and report RESULT_CANCELED at
    // once, before the user has touched the switch; so the answer is read in onResume.
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onPause() {
        super.onPause()
        if (awaitingOverlay) leftForSettings = true
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingOverlay || !leftForSettings) return
        // canDrawOverlays can lag the switch by a moment on some builds.
        window.decorView.postDelayed({
            if (isFinishing || !awaitingOverlay) return@postDelayed
            awaitingOverlay = false
            leftForSettings = false
            if (Settings.canDrawOverlays(this)) {
                next()
            } else {
                toast(R.string.screen_overlay_required)
                finish()
            }
        }, 400)
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { next() }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val start = Intent(this, ScreenTranslateService::class.java)
                .setAction(ScreenTranslateService.ACTION_START)
                .putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, start)
        } else {
            toast(R.string.screen_capture_denied)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && ScreenOverlayHost.service.value != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Reached from the "stopped" notification with the accessibility service on: it draws and
            // takes the screenshots, so there is nothing to ask for.
            runCatching {
                startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_START_TRUSTED))
            }
            finish()
            return
        }
        overlayAsked = savedInstanceState?.getBoolean(KEY_OVERLAY) ?: false
        notificationsAsked = savedInstanceState?.getBoolean(KEY_NOTIFICATIONS) ?: false
        // After a recreation a launched request is still pending and reports to its callback.
        if (savedInstanceState == null) next()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OVERLAY, overlayAsked)
        outState.putBoolean(KEY_NOTIFICATIONS, notificationsAsked)
    }

    private fun next() {
        when {
            // The accessibility service draws its own windows: no overlay permission needed then.
            !Settings.canDrawOverlays(this) && ScreenOverlayHost.service.value == null -> {
                if (overlayAsked) {
                    toast(R.string.screen_overlay_required)
                    finish()
                    return
                }
                overlayAsked = true
                awaitingOverlay = true
                toast(R.string.screen_overlay_allow)
                val page = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                runCatching { overlaySettings.launch(page) }.onFailure {
                    // Some vendor builds only have the list of all apps.
                    runCatching { overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }.onFailure { finish() }
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                notificationsAsked = true
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // The whole display, not one app: the bubble follows the user between apps.
                val consent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                runCatching { capture.launch(consent) }.onFailure {
                    toast(R.string.screen_capture_unavailable)
                    finish()
                }
            }
        }
    }

    private fun toast(@StringRes text: Int) =
        Toast.makeText(applicationContext, AppLocale.string(applicationContext, text), Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_OVERLAY = "overlay_asked"
        const val KEY_NOTIFICATIONS = "notifications_asked"
    }
}

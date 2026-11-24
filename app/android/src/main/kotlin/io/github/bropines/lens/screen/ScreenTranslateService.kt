package io.github.bropines.lens.screen

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.bropines.lens.LensApplication
import io.github.bropines.lens.MainActivity
import io.github.bropines.lens.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the screen capture and the bubble alive while the user is in other
 * apps. It lives exactly as long as one capture consent: when the user stops
 * the capture (from the status bar, the notification, the menu or the app) it
 * takes everything off the screen and ends.
 */
class ScreenTranslateService : Service() {
    private var capturer: FrameSource? = null
    private var translator: ScreenTranslator? = null
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_START_TRUSTED -> startTrusted()
            // Also a restart without its intent: the consent is gone, there is nothing to resume.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        try {
            // Android 14+: in the foreground as "media projection" before the projection is taken.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            if (translator != null) return
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = data?.let { manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), it) }
            if (projection == null) {
                stopSelf()
                return
            }
            // With the accessibility service on, every window goes through it, so the
            // translation can be opaque; the bubble and menu follow so they stay on top of it.
            val trusted = ScreenOverlayHost.service.value
            val ui = trusted ?: windowContext()
            val size = (ui.getSystemService(Context.WINDOW_SERVICE) as WindowManager).screenSize()
            run(ScreenCapturer(projection, size.x, size.y, resources.configuration.densityDpi, onStopped = ::stopSelf), ui, trusted)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * With the accessibility service on (Android 11+) its screenshots replace the
     * screen recording: no consent dialog, and no foreground "recording" service,
     * since the system keeps a process with a bound accessibility service alive.
     */
    private fun startTrusted() {
        if (translator != null) return
        val trusted = ScreenOverlayHost.service.value
        if (trusted == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            stopSelf()
            return
        }
        try {
            val size = (trusted.getSystemService(Context.WINDOW_SERVICE) as WindowManager).screenSize()
            run(AccessibilityCapturer(trusted, size.x, size.y), trusted, trusted)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun run(capture: FrameSource, ui: Context, trusted: AccessibilityService?) {
        ScreenOverlayHost.windowType =
            if (trusted != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        capturer = capture
        translator = ScreenTranslator(this, ui, (application as LensApplication).graph, capture, stop = ::stopSelf)
        ScreenTranslateState.running.value = true
        if (trusted != null) {
            // Its windows (and screenshots) die with the service: nothing would be left on screen to stop with.
            scope.launch {
                ScreenOverlayHost.service.first { it !== trusted }
                Toast.makeText(this@ScreenTranslateService, "Спецвозможность Lens выключена — перевод экрана остановлен", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }

    private fun fail(e: Exception) {
        Log.e(TAG, "could not start", e)
        Toast.makeText(this, "Не удалось включить перевод экрана: ${e.message}", Toast.LENGTH_LONG).show()
        stopSelf()
    }

    /** Overlay windows want a context made for them, so they follow the display's configuration. */
    private fun windowContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        return createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        translator?.onDisplayChanged(newConfig.densityDpi)
    }

    override fun onDestroy() {
        scope.cancel()
        translator?.close()
        translator = null
        capturer?.close()
        capturer = null
        ScreenTranslateState.running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Перевод экрана", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP), immutable,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_translate)
            .setContentTitle("Перевод экрана включён")
            .setContentText("Тап по пузырю — перевод, удержание — меню")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Остановить", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.bropines.lens.screen.START"
        const val ACTION_STOP = "io.github.bropines.lens.screen.STOP"
        const val ACTION_START_TRUSTED = "io.github.bropines.lens.screen.START_TRUSTED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "screen_translate"
        private const val NOTIFICATION_ID = 7201
        private const val TAG = "ScreenTranslate"
    }
}

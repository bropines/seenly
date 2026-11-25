package io.github.bropines.lens.screen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.bropines.lens.LensApplication
import io.github.bropines.lens.MainActivity
import io.github.bropines.lens.AppLocale
import io.github.bropines.lens.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the screen capture and the bubble alive while the user is in other
 * apps. It lives exactly as long as one capture consent: when the user stops
 * the capture (from the status bar, the notification, the menu or the app) it
 * takes everything off the screen and ends. When the system ends it instead
 * (the phone was locked, the accessibility service was switched off), it also
 * leaves a notification that turns it back on.
 */
class ScreenTranslateService : Service() {
    private var capturer: FrameSource? = null
    private var translator: ScreenTranslator? = null
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false

    /** The screen went off, came on, or was unlocked. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            translator?.onScreen(awake())
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) displayChanged()
        }
    }

    private val checkDisplay = Runnable { translator?.onDisplayChanged() }

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
            val capture = ScreenCapturer(projection, size.x, size.y, resources.configuration.densityDpi) {
                // Not by us: the phone was locked (Android 14+ ends a capture then), or the user ended it from the status bar.
                stoppedByTheSystem(R.string.screen_stopped_capture)
            }
            run(capture, ui, trusted)
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
            // Killed with its process (a task swiped away on MIUI), it comes back when the accessibility service does.
            val session = ScreenSession(this)
            session.started()
            val since = SystemClock.elapsedRealtime()
            scope.launch {
                while (true) {
                    delay(ScreenSession.BEAT_MS)
                    session.beat(SystemClock.elapsedRealtime() - since)
                }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun run(capture: FrameSource, ui: Context, trusted: AccessibilityService?) {
        ScreenOverlayHost.windowType =
            if (trusted != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        capturer = capture
        translator = ScreenTranslator(this, ui, (application as LensApplication).graph, capture, awake = ::awake, stop = ::stopSelf)
        ScreenTranslateState.running.value = true
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(STOPPED_ID)
        watch()
        if (trusted != null) {
            // Its windows (and screenshots) die with the service: nothing would be left on screen to stop with.
            scope.launch {
                ScreenOverlayHost.service.first { it !== trusted }
                Toast.makeText(this@ScreenTranslateService, AppLocale.string(this@ScreenTranslateService, R.string.screen_accessibility_off), Toast.LENGTH_LONG).show()
                stoppedByTheSystem(R.string.screen_stopped_accessibility)
            }
        }
    }

    /** Listens for what the translator has to follow: the screen going off, the display turning. */
    private fun watch() {
        if (watching) return
        watching = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, handler)
        translator?.onScreen(awake())
    }

    /** On, and past the lock screen: a locked phone has nothing on it to translate. */
    private fun awake(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    /**
     * The display may have turned. The window manager can learn of it a moment
     * after we are told, so it is asked now and twice more; and since display
     * events also come in bursts that have nothing to do with size (the refresh
     * rate), the asking waits for the burst to end.
     */
    private fun displayChanged() {
        handler.removeCallbacks(checkDisplay)
        for (wait in DISPLAY_CHECKS_MS) handler.postDelayed(checkDisplay, wait)
    }

    private fun fail(e: Exception) {
        Log.e(TAG, "could not start", e)
        Toast.makeText(this, AppLocale.string(this, R.string.screen_start_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
        stopSelf()
    }

    /** Ends, and leaves word of why with a way back: nobody asked for this stop. */
    private fun stoppedByTheSystem(@StringRes reason: Int) {
        Log.i(TAG, "stopped by the system")
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(manager)
            val restart = PendingIntent.getActivity(
                this, 2,
                Intent(this, ScreenPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = AppLocale.string(this, reason)
            manager.notify(
                STOPPED_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_screen_translate)
                    .setContentTitle(AppLocale.string(this, R.string.screen_stopped_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(restart)
                    .setAutoCancel(true)
                    .build(),
            )
        }
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
        displayChanged()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        translator?.onTrimMemory(level)
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacks(checkDisplay)
        if (watching) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) }
            watching = false
        }
        translator?.close()
        translator = null
        capturer?.close()
        capturer = null
        // A stop by the system's own hand never gets here: the process dies first, and the mark stays.
        ScreenSession(this).stopped()
        ScreenTranslateState.running.value = false
        super.onDestroy()
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, AppLocale.string(this, R.string.screen_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    private fun notification(): Notification {
        ensureChannel(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP), immutable,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_translate)
            .setContentTitle(AppLocale.string(this, R.string.screen_notification_title))
            .setContentText(AppLocale.string(this, R.string.screen_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, AppLocale.string(this, R.string.screen_notification_stop), stop)
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
        private const val STOPPED_ID = 7202
        private val DISPLAY_CHECKS_MS = longArrayOf(80L, 450L, 1_300L)
        private const val TAG = "ScreenTranslate"
    }
}

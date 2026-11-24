package io.github.bropines.lens.screen

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.provider.Settings
import io.github.bropines.lens.app.screen.ScreenTranslate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the screen translator's service is alive; the service itself keeps it true. */
internal object ScreenTranslateState {
    val running = MutableStateFlow(false)
}

/** The app's handle on the screen translator: starts the permission flow, stops the service. */
class AndroidScreenTranslate(
    private val context: Context,
    private val frontActivity: () -> Activity?,
) : ScreenTranslate {
    override val running: StateFlow<Boolean> get() = ScreenTranslateState.running

    override fun start() {
        if (ScreenTranslateState.running.value) return
        if (ScreenOverlayHost.service.value != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // The accessibility service draws and takes screenshots: no permission to ask for.
            context.startService(Intent(context, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_START_TRUSTED))
            return
        }
        val front = frontActivity()
        val intent = Intent(context, ScreenPermissionActivity::class.java)
        if (front != null) front.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun stop() {
        context.stopService(Intent(context, ScreenTranslateService::class.java))
    }

    override val opaque: StateFlow<Boolean> get() = ScreenOverlayHost.on

    override fun openOpaqueSettings() {
        val component = ComponentName(context, LensAccessibilityService::class.java).flattenToString()
        // Straight to this service's page where the system has one, else the list of all.
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val front = frontActivity()
        fun launch(intent: Intent) =
            if (front != null) front.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runCatching { launch(details) }.recoverCatching { launch(list) }
    }
}

/** What the bubble does. */
internal enum class ScreenMode {
    /** One tap translates what is on screen now; another tap anywhere puts it away. */
    Screen,

    /** Translates by itself every time the picture comes to rest. */
    Live,

    /** The same, inside a rectangle the user places. */
    Area,
}

/** The bubble's own state, kept across runs. Language and drawing settings are the app's. */
internal class ScreenPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("screen_translate", Context.MODE_PRIVATE)

    var mode: ScreenMode
        get() = ScreenMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: ScreenMode.Screen
        set(value) = prefs.edit().putString("mode", value.name).apply()

    /** The bubble rests against the right edge (or the left). */
    var bubbleRight: Boolean
        get() = prefs.getBoolean("bubble_right", true)
        set(value) = prefs.edit().putBoolean("bubble_right", value).apply()

    /** The bubble's top as a fraction of the screen height. */
    var bubbleY: Float
        get() = prefs.getFloat("bubble_y", 0.62f)
        set(value) = prefs.edit().putFloat("bubble_y", value).apply()

    /** The menu opens by itself the first time, so the long press is discovered. */
    var menuShown: Boolean
        get() = prefs.getBoolean("menu_shown", false)
        set(value) = prefs.edit().putBoolean("menu_shown", value).apply()

    /** The area, as fractions of the screen so it survives a rotation. */
    fun area(width: Int, height: Int): Rect {
        val f = RectF(
            prefs.getFloat("area_l", 0.08f),
            prefs.getFloat("area_t", 0.30f),
            prefs.getFloat("area_r", 0.92f),
            prefs.getFloat("area_b", 0.58f),
        )
        return Rect((f.left * width).toInt(), (f.top * height).toInt(), (f.right * width).toInt(), (f.bottom * height).toInt())
    }

    fun saveArea(rect: Rect, width: Int, height: Int) {
        prefs.edit()
            .putFloat("area_l", rect.left.toFloat() / width)
            .putFloat("area_t", rect.top.toFloat() / height)
            .putFloat("area_r", rect.right.toFloat() / width)
            .putFloat("area_b", rect.bottom.toFloat() / height)
            .apply()
    }
}

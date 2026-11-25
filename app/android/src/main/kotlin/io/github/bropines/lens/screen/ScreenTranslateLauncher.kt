package io.github.bropines.lens.screen

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
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

    /**
     * The area, as fractions of the screen so it survives a rotation. Upright
     * and sideways each remember their own (a chat one way, a game the other);
     * until the area has been placed sideways, the upright one is used there.
     */
    fun area(width: Int, height: Int): Rect {
        val wide = if (width > height) WIDE else ""
        fun part(name: String, default: Float) = prefs.getFloat(name + wide, prefs.getFloat(name, default))
        val f = RectF(part("area_l", 0.08f), part("area_t", 0.30f), part("area_r", 0.92f), part("area_b", 0.58f))
        return Rect((f.left * width).toInt(), (f.top * height).toInt(), (f.right * width).toInt(), (f.bottom * height).toInt())
    }

    fun saveArea(rect: Rect, width: Int, height: Int) {
        val wide = if (width > height) WIDE else ""
        prefs.edit()
            .putFloat("area_l$wide", rect.left.toFloat() / width)
            .putFloat("area_t$wide", rect.top.toFloat() / height)
            .putFloat("area_r$wide", rect.right.toFloat() / width)
            .putFloat("area_b$wide", rect.bottom.toFloat() / height)
            .apply()
    }

    private companion object {
        const val WIDE = "_wide"
    }
}

/**
 * Remembers that the screen translator was running on the accessibility
 * service, so that it comes back when the process does. Some systems (MIUI and
 * its kin) kill a process the moment its task is swiped away; Android then
 * starts the accessibility service again, and without this the bubble would
 * simply be gone. A stop the user asked for clears the mark.
 */
internal class ScreenSession(private val context: Context) {
    private val prefs = context.getSharedPreferences("screen_translate", Context.MODE_PRIVATE)

    /** Counts restarts of the device: time since boot means nothing across one. */
    private val boot: Int
        get() = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)

    fun started() {
        prefs.edit().putBoolean(ON, true).putInt(BOOT, boot).putLong(ALIVE_AT, SystemClock.elapsedRealtime()).apply()
    }

    /** Still running. Once it has run for a while, an earlier comeback no longer counts against the next. */
    fun beat(runningForMs: Long) {
        val edit = prefs.edit().putLong(ALIVE_AT, SystemClock.elapsedRealtime())
        if (runningForMs >= STEADY_MS) edit.putInt(COMEBACKS, 0)
        edit.apply()
    }

    fun stopped() {
        prefs.edit().putBoolean(ON, false).apply()
    }

    /**
     * Whether the translator should be started again now: it was running a
     * short while ago, in this same boot, and was never told to stop. Not
     * more than a few times in a row: something that keeps dying is not
     * brought back for ever.
     */
    fun comeBack(): Boolean {
        if (!prefs.getBoolean(ON, false)) return false
        val away = SystemClock.elapsedRealtime() - prefs.getLong(ALIVE_AT, 0)
        val comebacks = prefs.getInt(COMEBACKS, 0)
        if (prefs.getInt(BOOT, -1) != boot || away < 0 || away > COME_BACK_WITHIN_MS || comebacks >= MAX_COMEBACKS) {
            stopped()
            return false
        }
        prefs.edit().putInt(COMEBACKS, comebacks + 1).apply()
        return true
    }

    companion object {
        /** How often a running translator says it is alive. */
        const val BEAT_MS = 60_000L
        private const val ON = "trusted_on"
        private const val BOOT = "trusted_boot"
        private const val ALIVE_AT = "trusted_alive_at"
        private const val COMEBACKS = "trusted_comebacks"
        private const val COME_BACK_WITHIN_MS = 15 * 60_000L
        private const val STEADY_MS = 2 * 60_000L
        private const val MAX_COMEBACKS = 3
    }
}

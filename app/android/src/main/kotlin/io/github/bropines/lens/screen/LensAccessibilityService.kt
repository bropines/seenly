package io.github.bropines.lens.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An accessibility service that reads nothing and does nothing on its own: it
 * exists only for the window type it unlocks. Android lets a touch through an
 * ordinary overlay only when that overlay is at most 80% opaque, so the screen
 * translation would always show the original through it. An accessibility
 * overlay is trusted: fully opaque, and touches still reach the app below.
 *
 * The user turns it on in the system's accessibility settings; it subscribes
 * to no events and cannot read window content (see lens_accessibility.xml).
 */
class LensAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        ScreenOverlayHost.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenOverlayHost.disconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenOverlayHost.disconnected(this)
        super.onDestroy()
    }
}

/** Where the screen translator's windows go: the accessibility service when it is on, else ordinary overlays. */
internal object ScreenOverlayHost {
    private val _service = MutableStateFlow<AccessibilityService?>(null)
    private val _on = MutableStateFlow(false)

    /** The connected accessibility service, or null while it is off. */
    val service: StateFlow<AccessibilityService?> = _service.asStateFlow()

    /** Whether it is on, for the app's settings. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** The type every window of the running translator uses; chosen when it starts. */
    @Volatile
    var windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** Windows of this type may be fully opaque and still let touches through. */
    val trusted: Boolean get() = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun connected(service: AccessibilityService) {
        _service.value = service
        _on.value = true
    }

    fun disconnected(service: AccessibilityService) {
        if (_service.value === service) {
            _service.value = null
            _on.value = false
        }
    }
}

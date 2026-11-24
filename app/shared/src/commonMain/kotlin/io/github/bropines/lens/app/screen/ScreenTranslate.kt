package io.github.bropines.lens.app.screen

import kotlinx.coroutines.flow.StateFlow

/**
 * "Translate the screen": a floating bubble over other apps that captures the
 * screen and draws the translation in place. Only Android has it.
 */
interface ScreenTranslate {
    /** True while the bubble (and the screen capture behind it) is on. */
    val running: StateFlow<Boolean>

    /** Asks for whatever permissions are missing, then shows the bubble. */
    fun start()

    fun stop()

    /**
     * True while the app's accessibility service is on. Without it Android keeps
     * an overlay that lets touches through at most 80% opaque, so in the "auto"
     * and "area" modes the original shows through the translation.
     */
    val opaque: StateFlow<Boolean>

    /** Opens the system settings where the user turns that service on or off. */
    fun openOpaqueSettings()
}

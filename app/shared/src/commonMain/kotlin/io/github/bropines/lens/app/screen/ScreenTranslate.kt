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
}

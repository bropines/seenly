package io.github.bropines.lens.ondevice

import android.content.Context
import io.github.bropines.lens.app.ondevice.OnDeviceMl

/**
 * The foss flavor has no on-device ML: the google flavor's is Google's
 * closed-source ML Kit with Play services, which this flavor exists to leave
 * out. The app hides everything built on it.
 */
@Suppress("UNUSED_PARAMETER")
fun createOnDeviceMl(context: Context): OnDeviceMl? = null

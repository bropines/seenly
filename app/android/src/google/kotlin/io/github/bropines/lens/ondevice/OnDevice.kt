package io.github.bropines.lens.ondevice

import android.content.Context
import io.github.bropines.lens.app.ondevice.OnDeviceMl

/**
 * The google flavor's on-device ML: Google ML Kit. The foss flavor's source
 * set has this same function returning null, and nothing outside the two
 * names ML Kit, so its APK carries none of it.
 */
fun createOnDeviceMl(context: Context): OnDeviceMl? = MlKitOnDevice(context.applicationContext)

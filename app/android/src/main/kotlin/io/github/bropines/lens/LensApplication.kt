package io.github.bropines.lens

import android.app.Application
import android.content.ComponentCallbacks2
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.di.createAppGraph

class LensApplication : Application() {
    val services: AndroidPlatformServices by lazy { AndroidPlatformServices(this) }
    val graph: AppGraph by lazy { createAppGraph(services) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Out of sight, the in-memory scans are dead weight: history has them on disk.
        // A process kept alive by screen translation or its accessibility service is
        // otherwise exactly the one the system would rather were small.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) graph.scans.trim()
    }
}

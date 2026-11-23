package io.github.bropines.lens

import android.app.Application
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.di.createAppGraph

class LensApplication : Application() {
    val graph: AppGraph by lazy { createAppGraph(AndroidPlatformServices(this)) }
}

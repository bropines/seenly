package io.github.bropines.lens

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.di.createAppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LensApplication : Application() {
    val services: AndroidPlatformServices by lazy { AndroidPlatformServices(this) }
    val graph: AppGraph by lazy { createAppGraph(services) }
    private val scope = MainScope()
    private var followingUiLanguage = false

    override fun onCreate() {
        super.onCreate()
        AppLocale.applyDefault(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Before 13 the system has just reset the default locale to its own.
        AppLocale.applyDefault(this)
        // 13+: the language may have been changed in the system's settings.
        if (followingUiLanguage) scope.launch { adoptSystemUiLanguage() }
    }

    /**
     * Applies the UI language setting whenever it changes (in Settings or by an
     * import). Started by the activity: only its UI can change the setting.
     */
    fun followUiLanguage() {
        if (followingUiLanguage) return
        followingUiLanguage = true
        scope.launch {
            adoptSystemUiLanguage()
            graph.settings.settings.map { it.uiLanguage }.distinctUntilChanged().collect { tag ->
                if (AppLocale.apply(this@LensApplication, tag)) services.activity?.get()?.recreate()
            }
        }
        // The share sheet's entries follow the setting, and their labels the UI language.
        scope.launch {
            graph.settings.settings.map { it.shareTargets to it.uiLanguage }.distinctUntilChanged().collect { (targets, _) ->
                withContext(Dispatchers.IO) { ShareShortcuts.publish(AppLocale.wrap(this@LensApplication), targets) }
            }
        }
    }

    /** 13+: the system's per-app language wins over the stored setting, so Settings shows what is in effect. */
    private suspend fun adoptSystemUiLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val system = AppLocale.current(this)
        if (graph.settings.settings.first().uiLanguage != system) graph.settings.update { it.copy(uiLanguage = system) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Out of sight, the in-memory scans are dead weight: history has them on disk.
        // A process kept alive by screen translation or its accessibility service is
        // otherwise exactly the one the system would rather were small.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) graph.scans.trim()
    }
}

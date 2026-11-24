package io.github.bropines.lens.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.bropines.lens.app.LensApp
import io.github.bropines.lens.app.di.createAppGraph
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

fun main() {
    FileKit.init(appId = "lens-kmp")
    val graph = createAppGraph(DesktopPlatformServices())
    // Compose resources read the default locale; the UI language setting overrides the system's.
    val systemLocale = Locale.getDefault()
    val initialLanguage = runBlocking { graph.settings.settings.first().uiLanguage }
    application {
        val uiLanguage by remember { graph.settings.settings.map { it.uiLanguage }.distinctUntilChanged() }
            .collectAsState(initialLanguage)
        Locale.setDefault(uiLanguage?.let(Locale::forLanguageTag) ?: systemLocale)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Seenly",
            state = rememberWindowState(width = 1100.dp, height = 800.dp),
        ) {
            // A new language rebuilds the UI, so every string is looked up again.
            key(uiLanguage) { LensApp(graph) }
        }
    }
}

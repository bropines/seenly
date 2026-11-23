package io.github.bropines.lens.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.bropines.lens.app.LensApp
import io.github.bropines.lens.app.di.createAppGraph
import io.github.vinceglb.filekit.FileKit

fun main() {
    FileKit.init(appId = "lens-kmp")
    val graph = createAppGraph(DesktopPlatformServices())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Lens",
            state = rememberWindowState(width = 1100.dp, height = 800.dp),
        ) {
            LensApp(graph)
        }
    }
}

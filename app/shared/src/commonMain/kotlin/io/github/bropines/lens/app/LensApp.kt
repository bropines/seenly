package io.github.bropines.lens.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.home.HomeScreen
import io.github.bropines.lens.app.ui.settings.SettingsScreen
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel
import io.github.bropines.lens.app.ui.theme.LensTheme
import io.github.bropines.lens.app.ui.viewer.ViewerScreen
import io.github.bropines.lens.app.ui.viewer.ViewerViewModel
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface Route {
    data object Home : Route
    data object Viewer : Route
    data object Settings : Route
    data class TextTranslate(val initial: String = "") : Route
}

/**
 * The whole app. [incoming] carries images handed to the app from outside
 * (Android's share sheet, a file opened with it) and opens them in the viewer.
 */
@Composable
fun LensApp(graph: AppGraph, incoming: Flow<List<PlatformFile>> = emptyFlow()) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { addPlatformFileSupport() }.build()
    }
    val settings by graph.settings.settings.collectAsState(AppSettings())
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }

    LaunchedEffect(incoming) {
        incoming.collect { files ->
            graph.gallery.openFiles(files)
            if (backStack.lastOrNull() != Route.Viewer) backStack.add(Route.Viewer)
        }
    }

    LensTheme(settings.theme) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Route.Home> {
                    HomeScreen(
                        graph = graph,
                        settings = settings,
                        onOpenViewer = { backStack.add(Route.Viewer) },
                        onOpenSettings = { backStack.add(Route.Settings) },
                        onTranslateText = { backStack.add(Route.TextTranslate()) },
                    )
                }
                entry<Route.Viewer> {
                    val gallery = graph.gallery.current.collectAsState().value
                    if (gallery != null) {
                        val vm = viewModel { ViewerViewModel(gallery, graph.scans, graph.settings) }
                        ViewerScreen(
                            vm = vm,
                            services = graph.services,
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onOpenSettings = { backStack.add(Route.Settings) },
                            onTranslateText = { backStack.add(Route.TextTranslate(it)) },
                        )
                    }
                }
                entry<Route.Settings> {
                    SettingsScreen(graph.settings, settings, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
                entry<Route.TextTranslate> { route ->
                    val vm = viewModel { TextTranslateViewModel(graph.translator, graph.settings, route.initial) }
                    TextTranslateScreen(vm, graph.services, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
            },
        )
    }
}

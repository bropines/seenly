package io.github.bropines.lens.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.compose.setSingletonImageLoaderFactory
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.main.MainScreen
import io.github.bropines.lens.app.ui.main.Tab
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel
import io.github.bropines.lens.app.ui.theme.LensTheme
import io.github.bropines.lens.app.ui.viewer.ViewerScreen
import io.github.bropines.lens.app.ui.viewer.ViewerViewModel
import androidx.compose.runtime.rememberCoroutineScope
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.ui.camera.CameraScreen
import io.github.bropines.lens.app.ui.about.AboutScreen
import io.github.bropines.lens.app.ui.settings.TranslatorsScreen
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.camera_snapshot
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface Route {
    /** The tabs with the dock. */
    data object Main : Route
    data object Viewer : Route
    data object Camera : Route
    data object About : Route
    data object Translators : Route
    data class TextTranslate(val initial: String) : Route
}

/**
 * The whole app. [incoming] carries images handed to the app from outside
 * (Android's share sheet, a file opened with it, the assistant's screenshot)
 * and opens them in the viewer.
 */
@Composable
fun LensApp(graph: AppGraph, incoming: Flow<List<PlatformFile>> = emptyFlow()) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { addPlatformFileSupport() }
            // Pages are decoded large (up to 3072 px, software bitmaps for the exporter), so a
            // percentage of the heap like Coil's default holds a whole chapter. The pager keeps
            // its neighbours itself; the cache only has to make paging back instant.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
            .build()
    }
    val settings by graph.settings.settings.collectAsState(AppSettings())
    val backStack = remember { mutableStateListOf<Route>(Route.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.Images) }
    val scope = rememberCoroutineScope()
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    LaunchedEffect(incoming) {
        incoming.collect { files ->
            graph.gallery.openFiles(files)
            if (backStack.lastOrNull() != Route.Viewer) backStack.add(Route.Viewer)
        }
    }

    LensTheme(settings.theme) {
        NavDisplay(
            backStack = backStack,
            onBack = ::pop,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // Short and decisive: a small slide with a fade, no bounce.
            transitionSpec = {
                (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(160))) togetherWith
                    fadeOut(tween(110))
            },
            popTransitionSpec = {
                fadeIn(tween(160)) togetherWith
                    (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(140)))
            },
            entryProvider = entryProvider {
                entry<Route.Main> {
                    MainScreen(
                        graph = graph,
                        settings = settings,
                        tab = tab,
                        onTab = { tab = it },
                        onOpenViewer = { backStack.add(Route.Viewer) },
                        onOpenCamera = { backStack.add(Route.Camera) },
                        onOpenAbout = { backStack.add(Route.About) },
                        onOpenTranslators = { backStack.add(Route.Translators) },
                        onOpenHistory = { entry ->
                            scope.launch {
                                val item = GalleryItem(PlatformFile(entry.imagePath), entry.title)
                                // Opening an old scan costs no request when the language still matches.
                                if (entry.targetLanguage == settings.targetLanguage) {
                                    graph.history.load(entry)?.let { graph.scans.prime(item, settings, it) }
                                }
                                graph.gallery.openItems(listOf(item), entry.title)
                                backStack.add(Route.Viewer)
                            }
                        },
                    )
                }
                entry<Route.About> { AboutScreen(graph.services, onBack = ::pop) }
                entry<Route.Translators> { TranslatorsScreen(graph.settings, settings, graph.translator, onBack = ::pop) }
                entry<Route.Camera> {
                    val pick = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
                        if (!files.isNullOrEmpty()) {
                            graph.gallery.openFiles(files)
                            backStack[backStack.lastIndex] = Route.Viewer
                        }
                    }
                    val snapshot = stringResource(Res.string.camera_snapshot)
                    CameraScreen(
                        services = graph.services,
                        onCaptured = { file ->
                            graph.gallery.openItems(listOf(GalleryItem(file, snapshot)), snapshot)
                            // Replace the camera: back from the photo goes home, like Lens.
                            backStack[backStack.lastIndex] = Route.Viewer
                        },
                        onPickImages = { pick.launch() },
                        onBack = ::pop,
                    )
                }
                entry<Route.Viewer> {
                    val gallery = graph.gallery.current.collectAsState().value
                    if (gallery != null) {
                        val vm = viewModel { ViewerViewModel(gallery, graph.scans, graph.settings) }
                        ViewerScreen(
                            vm = vm,
                            services = graph.services,
                            translator = graph.translator,
                            onBack = ::pop,
                            onTranslateText = { backStack.add(Route.TextTranslate(it)) },
                        )
                    }
                }
                entry<Route.TextTranslate> { route ->
                    val vm = viewModel { TextTranslateViewModel(graph.translator, graph.settings, route.initial) }
                    TextTranslateScreen(vm, graph.services, onBack = ::pop)
                }
            },
        )
    }
}

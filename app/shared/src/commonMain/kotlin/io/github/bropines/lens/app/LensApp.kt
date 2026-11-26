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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import io.github.bropines.lens.app.ui.theme.LensTheme
import io.github.bropines.lens.app.ui.viewer.ViewerHost
import io.github.bropines.lens.app.ui.gallery.HeroRegistry
import io.github.bropines.lens.app.ui.gallery.LocalHeroRegistry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.bropines.lens.app.data.GalleryItem
import io.github.bropines.lens.app.data.GalleryOrigin
import io.github.bropines.lens.app.data.ViewMode
import io.github.bropines.lens.app.ui.camera.CameraScreen
import io.github.bropines.lens.app.ui.about.AboutScreen
import io.github.bropines.lens.app.ui.settings.SettingsPage
import io.github.bropines.lens.app.ui.settings.SettingsPageScreen
import io.github.bropines.lens.app.ui.settings.TranslatorsScreen
import io.github.bropines.lens.app.ui.account.AccountRefusedDialog
import io.github.bropines.lens.app.ui.account.AccountScreen
import io.github.bropines.lens.app.ui.account.GoogleSignInScreen
import io.github.bropines.lens.app.ui.ondevice.OnDeviceScreen
import io.github.bropines.lens.app.ui.update.UpdateDialog
import io.github.bropines.lens.app.ui.update.WhatsNewDialog
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.camera_snapshot
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Pictures handed to the app from outside, and what to open them with (null: the translation). */
data class Incoming(val files: List<PlatformFile>, val mode: ViewMode? = null)

sealed interface Route {
    /** The tabs with the dock. */
    data object Main : Route
    data object Camera : Route
    data object About : Route
    data object Translators : Route
    data object OnDevice : Route
    data object Account : Route
    data object GoogleSignIn : Route

    /** One of the pages behind the Settings tab's tiles. */
    data class Settings(val page: SettingsPage) : Route
}

/**
 * The whole app. [incoming] carries images handed to the app from outside
 * (Android's share sheet, a file opened with it, the assistant's screenshot)
 * and opens them in the viewer.
 *
 * The viewer is not a screen in the back stack: it is a layer over whatever
 * opened it, shown while a gallery is open (see ViewerHost).
 */
@Composable
fun LensApp(graph: AppGraph, incoming: Flow<Incoming> = emptyFlow()) {
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
    val gallery by graph.gallery.current.collectAsState()
    // Where the thumbnails on screen are, for pictures to fly between them and the viewer.
    val heroes = remember { HeroRegistry() }
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    LaunchedEffect(incoming) {
        incoming.collect { graph.gallery.openFiles(it.files, mode = it.mode, fromOutside = true) }
    }

    // The update check and "What's new" belong to the app coming up, not to any one screen.
    LaunchedEffect(Unit) { graph.updates.onAppStart() }
    // Back from the system page that allows installing apps: an update waiting for it installs.
    LifecycleResumeEffect(Unit) {
        graph.updates.onResumed()
        onPauseOrDispose { }
    }

    LensTheme(settings.theme, settings.themeColor, settings.amoled) {
        UpdateDialog(graph.updates)
        WhatsNewDialog(graph.updates, graph.services)
        AccountRefusedDialog(graph.account, onOpen = { if (backStack.lastOrNull() != Route.Account) backStack.add(Route.Account) })
        CompositionLocalProvider(LocalHeroRegistry provides heroes) {
        Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = ::pop,
            // Beneath a viewer that hides it all, the screens need not be drawn.
            modifier = Modifier.graphicsLayer { alpha = if (gallery != null && heroes.covered) 0f else 1f },
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
                        // Opening a gallery is what shows the viewer.
                        onOpenViewer = {},
                        onOpenCamera = { backStack.add(Route.Camera) },
                        onOpen = { backStack.add(it) },
                        // An old scan is its own stored result: opening it costs no request.
                        onOpenHistory = { entry -> graph.gallery.openHistory(listOf(entry), 0) },
                    )
                }
                entry<Route.Settings> { route ->
                    SettingsPageScreen(route.page, graph, settings, onOpen = { backStack.add(it) }, onBack = ::pop)
                }
                entry<Route.About> { AboutScreen(graph.services, onBack = ::pop) }
                entry<Route.Translators> { TranslatorsScreen(graph.settings, settings, graph.translator, onBack = ::pop) }
                entry<Route.OnDevice> {
                    graph.services.onDevice?.let { OnDeviceScreen(graph.settings, settings, it, onBack = ::pop) }
                }
                entry<Route.Account> {
                    AccountScreen(
                        account = graph.account,
                        repository = graph.settings,
                        settings = settings,
                        onSignIn = { backStack.add(Route.GoogleSignIn) },
                        onBack = ::pop,
                    )
                }
                entry<Route.GoogleSignIn> {
                    GoogleSignInScreen(
                        // The page's cookies are a signed-in session or nothing: connect() tells which.
                        onCookies = { cookies -> graph.account.connect(cookies).also { if (it) pop() } },
                        onBack = ::pop,
                    )
                }
                entry<Route.Camera> {
                    val pick = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
                        if (!files.isNullOrEmpty()) {
                            // Picked from the camera screen, they are the camera's: its remembered mode applies.
                            graph.gallery.openFiles(files, mode = settings.cameraMode, origin = GalleryOrigin.Camera)
                            pop()
                        }
                    }
                    val snapshot = stringResource(Res.string.camera_snapshot)
                    CameraScreen(
                        services = graph.services,
                        onCaptured = { file ->
                            graph.gallery.openItems(listOf(GalleryItem(file, snapshot)), snapshot, origin = GalleryOrigin.Camera, mode = settings.cameraMode)
                            // The camera goes as the photo opens: back from the photo goes home, like Lens.
                            pop()
                        },
                        onPickImages = { pick.launch() },
                        onBack = ::pop,
                    )
                }
            },
        )
        gallery?.let { open ->
            ViewerHost(graph, open, onClose = {
                graph.gallery.close()
                // A picture another app handed over: done with it, back to that app, not to Seenly's home.
                if (open.fromOutside) graph.services.returnToCaller()
            })
        }
        }
        }
    }
}

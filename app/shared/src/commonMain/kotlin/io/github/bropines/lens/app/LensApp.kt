package io.github.bropines.lens.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
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

private fun cameraIn(): ContentTransform =
    (fadeIn(tween(200)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.92f)) togetherWith
        // The screen beneath stays as it is until the camera covers it (a fade that barely fades).
        fadeOut(tween(260), targetAlpha = 0.99f)

private fun cameraOut(): ContentTransform =
    (EnterTransition.None togetherWith (fadeOut(tween(200)) + scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 0.92f)))
        // The leaving camera stays on top while it fades, over the screen beneath.
        .apply { targetContentZIndex = -1f }

private val CameraTransitions: Map<String, Any> =
    NavDisplay.transitionSpec { cameraIn() } +
        NavDisplay.popTransitionSpec { cameraOut() } +
        NavDisplay.predictivePopTransitionSpec { _ -> cameraBackGesture() }

/**
 * Following a back swipe: the camera shrinks as far as the finger has gone and only fades near the
 * end, so a light touch barely moves it instead of dissolving it at once.
 */
private fun cameraBackGesture(): ContentTransform =
    (EnterTransition.None togetherWith
        (scaleOut(tween(400, easing = LinearEasing), targetScale = 0.8f) + fadeOut(tween(400, easing = { t -> t * t * t }))))
        .apply { targetContentZIndex = -1f }

/** The back stack as names ("Camera", "Settings:Network"), which a saved instance state can hold. */
private val BackStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack ->
        stack.map { route ->
            when (route) {
                is Route.Settings -> "Settings:${route.page.name}"
                else -> route::class.simpleName.orEmpty()
            }
        }
    },
    restore = { names ->
        val routes = names.mapNotNull { name ->
            when {
                name.startsWith("Settings:") -> SettingsPage.entries.firstOrNull { it.name == name.removePrefix("Settings:") }?.let { Route.Settings(it) }
                else -> listOf(Route.Main, Route.Camera, Route.About, Route.Translators, Route.OnDevice, Route.Account, Route.GoogleSignIn)
                    .firstOrNull { it::class.simpleName == name }
            }
        }
        mutableStateListOf<Route>().apply { addAll(routes.ifEmpty { listOf(Route.Main) }) }
    },
)

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
    // The settings already loaded (the app outlives its activity): a new activity starts in the
    // chosen theme instead of the default one for its first frames.
    val settings by graph.settings.current.collectAsState()
    // Saved with the activity: a recreated one (turned phone, dark mode, process death) opens on
    // the same screen, the camera too, instead of falling back to the tabs.
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Route>(Route.Main) }
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
        graph.network.onForeground()
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
                        onCookies = { cookies -> graph.account.connect(cookies) },
                        // Asked by the page itself once signed in; the screen closes with the answer.
                        onEmail = { email ->
                            if (email != null) graph.account.setEmail(email)
                            pop()
                        },
                        onBack = ::pop,
                    )
                }
                // The camera rises over the screen that opened it and sinks back into it: that screen
                // stays fully drawn underneath, so no black-to-light cross-fade passes through grey.
                entry<Route.Camera>(metadata = CameraTransitions) {
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
                        light = settings.cameraLight,
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

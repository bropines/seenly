package io.github.bropines.lens.app.ui.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bropines.lens.app.data.Gallery
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel

/**
 * The viewer as a layer over the app rather than a screen of its own in the
 * back stack: what it was opened from stays alive beneath it, so a picture
 * can fly out of its thumbnail and back into it, and show what it goes back
 * to while it is pulled away.
 *
 * It lives as long as [gallery] is the open one; another gallery (a picture
 * shared while one is being read) gets a viewer and a view model of its own.
 */
@Composable
fun ViewerHost(graph: AppGraph, gallery: Gallery, onClose: () -> Unit) {
    key(gallery.id) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides rememberOwnViewModelStore()) {
            val vm = viewModel { ViewerViewModel(gallery, graph.scans, graph.settings, graph.gallery) }
            // "Open in translator" from a sheet: the text translator over the picture, back to the picture.
            var translating by rememberSaveable { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxSize()) {
                ViewerScreen(
                    vm = vm,
                    services = graph.services,
                    translator = graph.translator,
                    onBack = onClose,
                    onTranslateText = { translating = it },
                )
                AnimatedContent(
                    targetState = translating,
                    transitionSpec = {
                        (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(140)))
                    },
                    label = "translator",
                ) { text ->
                    if (text != null) {
                        val textVm = viewModel(key = "text:$text") { TextTranslateViewModel(graph.translator, graph.settings, text) }
                        NavigationBackHandler(
                            state = rememberNavigationEventState(NavigationEventInfo.None),
                            onBackCompleted = { translating = null },
                        )
                        TextTranslateScreen(textVm, graph.services, onBack = { translating = null })
                    }
                }
            }
        }
    }
}

/** A store for the viewer's view models that goes when the viewer does. */
@Composable
private fun rememberOwnViewModelStore(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

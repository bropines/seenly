package io.github.bropines.lens.app.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.lens.app.Route
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.history.HistoryEntry
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.history.HistoryScreen
import io.github.bropines.lens.app.ui.home.HomeScreen
import io.github.bropines.lens.app.ui.settings.SettingsScreen
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class Tab(val label: StringResource, val icon: ImageVector, val selectedIcon: ImageVector) {
    Images(Res.string.tab_images, Icons.Outlined.Image, Icons.Rounded.Image),
    History(Res.string.tab_history, Icons.Outlined.History, Icons.Rounded.History),
    Text(Res.string.tab_text, Icons.Outlined.Translate, Icons.Rounded.Translate),
    Settings(Res.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/**
 * The top-level places, switched from the dock: two tabs, the camera in the
 * middle as the main action, two tabs.
 */
@Composable
fun MainScreen(
    graph: AppGraph,
    settings: AppSettings,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenViewer: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenHistory: (HistoryEntry) -> Unit,
    /** Opens a page from Settings: one of its own, or the translators, the account, About. */
    onOpen: (Route) -> Unit = {},
) {
    val textVm = viewModel { TextTranslateViewModel(graph.translator, graph.settings, "") }
    // An update found on start is offered in passing: a snackbar that waits, with the notes one tap away.
    val snackbar = remember { SnackbarHostState() }
    val offered by graph.updates.prompt.collectAsState()
    val offer = offered?.let { stringResource(Res.string.update_available, it.version) }
    val details = stringResource(Res.string.update_details)
    LaunchedEffect(offer) {
        if (offer == null) return@LaunchedEffect
        when (snackbar.showSnackbar(offer, actionLabel = details, withDismissAction = true, duration = SnackbarDuration.Indefinite)) {
            SnackbarResult.ActionPerformed -> graph.updates.showDetails()
            SnackbarResult.Dismissed -> graph.updates.dismissPrompt()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ShortNavigationBar {
                DockItem(Tab.Images, tab, onTab)
                DockItem(Tab.History, tab, onTab)
                Box(contentAlignment = Alignment.Center) {
                    LargeFloatingActionButton(
                        onClick = onOpenCamera,
                        shape = MaterialTheme.shapes.extraLarge,
                        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                        modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = stringResource(Res.string.camera), modifier = Modifier.size(30.dp)) }
                }
                DockItem(Tab.Text, tab, onTab)
                DockItem(Tab.Settings, tab, onTab)
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            // Quick and flat: tabs are siblings, not a hierarchy.
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            label = "tab",
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    Tab.Images -> HomeScreen(graph, settings, onOpenViewer = onOpenViewer)
                    Tab.History -> HistoryScreen(graph.history, onOpen = onOpenHistory)
                    Tab.Text -> TextTranslateScreen(textVm, graph.services, onBack = null)
                    Tab.Settings -> SettingsScreen(graph, settings, onOpen)
                }
            }
        }
    }
}

@Composable
private fun DockItem(item: Tab, current: Tab, onTab: (Tab) -> Unit) {
    ShortNavigationBarItem(
        selected = item == current,
        onClick = { onTab(item) },
        icon = { Icon(if (item == current) item.selectedIcon else item.icon, contentDescription = null) },
        label = { Text(stringResource(item.label), maxLines = 1) },
    )
}

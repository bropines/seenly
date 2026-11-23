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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.history.HistoryEntry
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.history.HistoryScreen
import io.github.bropines.lens.app.ui.home.HomeScreen
import io.github.bropines.lens.app.ui.settings.SettingsScreen
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Images("Картинки", Icons.Outlined.Image, Icons.Rounded.Image),
    History("История", Icons.Outlined.History, Icons.Rounded.History),
    Text("Текст", Icons.Outlined.Translate, Icons.Rounded.Translate),
    Settings("Настройки", Icons.Outlined.Settings, Icons.Rounded.Settings),
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
) {
    val textVm = viewModel { TextTranslateViewModel(graph.translator, graph.settings, "") }
    Scaffold(
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
                    ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = "Камера", modifier = Modifier.size(30.dp)) }
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
                    Tab.Settings -> SettingsScreen(graph.settings, settings, onBack = null)
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
        label = { Text(item.label, maxLines = 1) },
    )
}

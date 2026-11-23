package io.github.bropines.lens.app.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.home.HomeScreen
import io.github.bropines.lens.app.ui.settings.SettingsScreen
import io.github.bropines.lens.app.ui.text.TextTranslateScreen
import io.github.bropines.lens.app.ui.text.TextTranslateViewModel

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Images("Картинки", Icons.Outlined.Image, Icons.Rounded.Image),
    Text("Текст", Icons.Outlined.Translate, Icons.Rounded.Translate),
    Settings("Настройки", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** The three top-level places, switched from the dock at the bottom. */
@Composable
fun MainScreen(
    graph: AppGraph,
    settings: AppSettings,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenViewer: () -> Unit,
) {
    val textVm = viewModel { TextTranslateViewModel(graph.translator, graph.settings, "") }
    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { item ->
                    ShortNavigationBarItem(
                        selected = item == tab,
                        onClick = { onTab(item) },
                        icon = { Icon(if (item == tab) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
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
                    Tab.Text -> TextTranslateScreen(textVm, graph.services, onBack = null)
                    Tab.Settings -> SettingsScreen(graph.settings, settings, onBack = null)
                }
            }
        }
    }
}

package io.github.bropines.lens.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.ui.components.LanguageBar
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.PlatformFileSerializer
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    settings: AppSettings,
    onOpenViewer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun openFolder(folder: PlatformFile) = scope.launch {
        graph.services.persistAccess(folder)
        if (graph.gallery.openFolder(folder) == null) {
            snackbar.showSnackbar("В папке «${folder.name}» нет картинок")
        } else {
            val serialised = Json.encodeToString(PlatformFileSerializer, folder)
            graph.settings.update { it.copy(lastFolder = serialised) }
            onOpenViewer()
        }
    }

    val pickImages = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) {
            graph.gallery.openFiles(files)
            onOpenViewer()
        }
    }
    val pickFolder = rememberDirectoryPickerLauncher { folder -> folder?.let(::openFolder) }
    val lastFolder = remember(settings.lastFolder) {
        settings.lastFolder?.let { runCatching { Json.decodeFromString(PlatformFileSerializer, it) }.getOrNull() }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Lens") },
                subtitle = { Text("Перевод с картинок") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        // Everything sits at the bottom, nearest the thumb, the main action lowest;
        // it still scrolls when a small screen cannot hold it all.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        Column(
            // No overscroll stretch: when everything fits there is nothing to pull.
            Modifier.verticalScroll(rememberScrollState(), overscrollEffect = null).heightIn(min = maxHeight).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            Text(
                "Поделитесь картинкой из любого приложения — она откроется здесь сразу с переводом.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastFolder != null) {
                Card(
                    onClick = { openFolder(lastFolder) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    ListItem(
                        headlineContent = { Text("Продолжить: ${lastFolder.name}") },
                        supportingContent = { Text("Последняя открытая папка") },
                        leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Переводить", style = MaterialTheme.typography.titleMedium)
                    LanguageBar(
                        source = settings.sourceLanguage,
                        target = settings.targetLanguage,
                        onSource = { code -> scope.launch { graph.settings.update { it.copy(sourceLanguage = code) } } },
                        onTarget = { code -> scope.launch { graph.settings.update { it.copy(targetLanguage = code) } } },
                    )
                }
            }
            graph.services.screenTranslate?.let { screen ->
                val running by screen.running.collectAsState()
                BigAction(
                    icon = Icons.Rounded.Translate,
                    title = if (running) "Остановить перевод экрана" else "Перевод экрана",
                    subtitle = if (running) "Пузырь поверх приложений включён" else "Пузырь поверх других приложений: экран, авто при прокрутке, область",
                    primary = false,
                    onClick = { if (running) screen.stop() else screen.start() },
                )
            }
            BigAction(
                icon = Icons.Rounded.FolderOpen,
                title = "Открыть папку",
                subtitle = "Листать все картинки папки свайпом — для глав манги",
                primary = false,
                onClick = { pickFolder.launch() },
            )
            BigAction(
                icon = Icons.Rounded.Image,
                title = "Открыть картинки",
                subtitle = "Одну или несколько из галереи",
                primary = true,
                onClick = { pickImages.launch() },
            )
        }
        }
    }
}

/** A large, self-explaining action: icon, what it does, and when to use it. */
@Composable
private fun BigAction(icon: ImageVector, title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
            }
        }
    }
}

package io.github.bropines.lens.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    onOpenSettings: () -> Unit,
    onTranslateText: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Lens") },
                subtitle = { Text("Перевод с картинок") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Настройки") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Язык перевода", style = MaterialTheme.typography.titleMedium)
                    LanguageBar(
                        source = settings.sourceLanguage,
                        target = settings.targetLanguage,
                        onSource = { code -> scope.launch { graph.settings.update { it.copy(sourceLanguage = code) } } },
                        onTarget = { code -> scope.launch { graph.settings.update { it.copy(targetLanguage = code) } } },
                    )
                }
            }

            Button(
                onClick = { pickImages.launch() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Text("Открыть картинки", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { pickFolder.launch() },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Папка")
                }
                OutlinedButton(
                    onClick = onTranslateText,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Текст")
                }
            }

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

            Text(
                "Поделитесь картинкой из любого приложения — она откроется здесь сразу с переводом.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

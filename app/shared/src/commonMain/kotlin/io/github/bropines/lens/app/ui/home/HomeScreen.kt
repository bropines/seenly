package io.github.bropines.lens.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import io.github.bropines.lens.app.ui.components.isWide
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
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
            snackbar.showSnackbar(getString(Res.string.home_folder_empty, folder.name))
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

    val hint: @Composable () -> Unit = {
        Text(
            stringResource(Res.string.home_share_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val continueCard: @Composable () -> Unit = {
        if (lastFolder != null) {
            Card(
                onClick = { openFolder(lastFolder) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.home_continue, lastFolder.name)) },
                    supportingContent = { Text(stringResource(Res.string.home_last_folder)) },
                    leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
    }
    val languages: @Composable () -> Unit = {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.home_translate), style = MaterialTheme.typography.titleMedium)
                LanguageBar(
                    source = settings.sourceLanguage,
                    target = settings.targetLanguage,
                    onSource = { code -> scope.launch { graph.settings.update { it.copy(sourceLanguage = code) } } },
                    onTarget = { code -> scope.launch { graph.settings.update { it.copy(targetLanguage = code) } } },
                )
            }
        }
    }
    val actions: @Composable () -> Unit = {
        graph.services.screenTranslate?.let { screen ->
            val running by screen.running.collectAsState()
            BigAction(
                icon = Icons.Rounded.Translate,
                title = stringResource(if (running) Res.string.home_screen_stop else Res.string.screen_translation),
                subtitle = stringResource(if (running) Res.string.home_screen_running else Res.string.home_screen_desc),
                primary = false,
                onClick = { if (running) screen.stop() else screen.start() },
            )
        }
        BigAction(
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(Res.string.home_open_folder),
            subtitle = stringResource(Res.string.home_open_folder_desc),
            primary = false,
            onClick = { pickFolder.launch() },
        )
        BigAction(
            icon = Icons.Rounded.Image,
            title = stringResource(Res.string.open_images),
            subtitle = stringResource(Res.string.home_open_images_desc),
            primary = true,
            onClick = { pickImages.launch() },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (isWide()) {
            // Turned on its side: no tall title bar; what Seenly is and translates into on the left,
            // what to open on the right, both reaching down to the thumbs.
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { _ ->
                Row(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState(), overscrollEffect = null).heightIn(min = maxHeight),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                        ) {
                            Column {
                                Text("Seenly", style = MaterialTheme.typography.headlineLarge)
                                Text(stringResource(Res.string.home_subtitle), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            hint()
                            continueCard()
                            languages()
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState(), overscrollEffect = null).heightIn(min = maxHeight),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                        ) { actions() }
                    }
                }
            }
        } else {
            Scaffold(
                topBar = {
                    LargeFlexibleTopAppBar(
                        title = { Text("Seenly") },
                        subtitle = { Text(stringResource(Res.string.home_subtitle)) },
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
                        hint()
                        continueCard()
                        languages()
                        actions()
                    }
                }
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

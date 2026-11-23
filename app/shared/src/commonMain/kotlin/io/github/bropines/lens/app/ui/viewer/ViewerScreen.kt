package io.github.bropines.lens.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.overlay.OverlayMode

@Composable
fun ViewerScreen(
    vm: ViewerViewModel,
    services: PlatformServices,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onTranslateText: (String) -> Unit,
) {
    val items = vm.gallery.items
    val pager = rememberPagerState(initialPage = vm.gallery.startIndex) { items.size }
    val pages by vm.pages.collectAsState()
    val settings by vm.settings.collectAsState()
    var mode by rememberSaveable { mutableStateOf(OverlayMode.Translated) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var showText by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(vm::onPageVisible) }

    val current = pages[pager.currentPage]
    val result = (current as? PageState.Ready)?.result

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items[it].key },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PageView(
                item = items[index],
                state = pages[index],
                mode = mode,
                onTap = { chrome = !chrome },
            )
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Column {
                        Text(items[pager.currentPage].name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (items.size > 1) {
                            Text(
                                "${pager.currentPage + 1} / ${items.size} · ${Languages.nameOf(settings.targetLanguage)}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Настройки") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }

        // Scan status for the visible page, centred above the toolbar.
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) {
            when (current) {
                PageState.Loading, null -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Text("Распознаю…", Modifier.padding(start = 8.dp))
                    }
                }
                is PageState.Failed -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                        Text(current.message, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        Button(onClick = { vm.retry(pager.currentPage) }) { Text("Ещё раз") }
                    }
                }
                is PageState.Ready -> Unit
            }
        }

        AnimatedVisibility(
            chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            ViewerToolbar(
                mode = mode,
                onMode = { mode = it },
                hasText = result != null && result.paragraphs.isNotEmpty(),
                hasTranslation = result?.translation != null,
                onShowText = { showText = true },
                onCopyOriginal = { result?.let { services.copyText(it.text) } },
                onCopyTranslation = { result?.translation?.let(services::copyText) },
                onShareText = { result?.let { services.shareText(shareableText(it)) } },
            )
        }
    }

    if (showText && result != null) {
        TextSheet(
            result = result,
            services = services,
            onDismiss = { showText = false },
            onTranslateWithGoogle = { showText = false; onTranslateText(it) },
        )
    }
}

@Composable
private fun ViewerToolbar(
    mode: OverlayMode,
    onMode: (OverlayMode) -> Unit,
    hasText: Boolean,
    hasTranslation: Boolean,
    onShowText: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyTranslation: () -> Unit,
    onShareText: () -> Unit,
) {
    var copyMenu by rememberSaveable { mutableStateOf(false) }
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconToggleButton(checked = mode == OverlayMode.Translated, onCheckedChange = { onMode(OverlayMode.Translated) }) {
            Icon(Icons.Rounded.Translate, contentDescription = "Перевод")
        }
        IconToggleButton(checked = mode == OverlayMode.Original, onCheckedChange = { onMode(OverlayMode.Original) }) {
            Icon(Icons.Rounded.Image, contentDescription = "Оригинал")
        }
        IconButton(onClick = onShowText, enabled = hasText) { Icon(Icons.Rounded.Notes, contentDescription = "Текст") }
        Box {
            IconButton(onClick = { copyMenu = true }, enabled = hasText) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Копировать") }
            DropdownMenu(expanded = copyMenu, onDismissRequest = { copyMenu = false }) {
                DropdownMenuItem(text = { Text("Копировать оригинал") }, onClick = { copyMenu = false; onCopyOriginal() })
                DropdownMenuItem(
                    text = { Text("Копировать перевод") },
                    enabled = hasTranslation,
                    onClick = { copyMenu = false; onCopyTranslation() },
                )
            }
        }
        IconButton(onClick = onShareText, enabled = hasText) { Icon(Icons.Rounded.Share, contentDescription = "Поделиться") }
    }
}

/** Original and translation, paragraph by paragraph: what "share" and "export text" produce. */
fun shareableText(result: io.github.bropines.lens.protocol.ScanResult): String =
    result.paragraphs.joinToString("\n\n") { p ->
        listOfNotNull(p.text, p.translation?.text).joinToString("\n")
    }

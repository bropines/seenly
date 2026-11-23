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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.platform.encodePng
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.renderTranslatedBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var tappedParagraph by remember { mutableStateOf<Int?>(null) }
    var zoomed by remember { mutableStateOf(false) }
    val renders = remember { mutableStateMapOf<Int, PageRender>() }
    val scope = rememberCoroutineScope()
    val style = when (settings.readingLayout) {
        ReadingLayout.InPlace -> OverlayStyle.Chromium
        ReadingLayout.Reflow -> OverlayStyle.Manga
    }

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { zoomed = false; vm.onPageVisible(it) } }

    val current = pages[pager.currentPage]
    val result = (current as? PageState.Ready)?.result

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items[it].key },
            // A zoomed page pans with one finger; it must not turn the page.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PageView(
                item = items[index],
                state = pages[index],
                mode = mode,
                style = style,
                onTap = { chrome = !chrome },
                onParagraphTap = { if (index == pager.currentPage) tappedParagraph = it },
                onZoomed = { if (index == pager.currentPage) zoomed = it },
                onRender = { render -> if (render == null) renders.remove(index) else renders[index] = render },
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
                canExportImage = result != null && renders[pager.currentPage]?.overlay != null,
                onExportImage = {
                    val render = renders[pager.currentPage]
                    val overlay = render?.overlay
                    if (render != null && overlay != null && result != null) {
                        val name = items[pager.currentPage].name.substringBeforeLast('.') + "-translated.png"
                        scope.launch {
                            val png = withContext(Dispatchers.Default) {
                                renderTranslatedBitmap(render.bitmap, result, style, overlay).encodePng()
                            }
                            services.shareImage(png, name)
                        }
                    }
                },
            )
        }
    }

    val paragraph = tappedParagraph?.let { result?.paragraphs?.getOrNull(it) }
    if (paragraph != null) {
        ParagraphSheet(
            paragraph = paragraph,
            services = services,
            onDismiss = { tappedParagraph = null },
            onTranslateWithGoogle = { tappedParagraph = null; onTranslateText(it) },
        )
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
    canExportImage: Boolean,
    onExportImage: () -> Unit,
) {
    var copyMenu by rememberSaveable { mutableStateOf(false) }
    var shareMenu by rememberSaveable { mutableStateOf(false) }
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
        Box {
            IconButton(onClick = { shareMenu = true }, enabled = hasText) { Icon(Icons.Rounded.Share, contentDescription = "Поделиться") }
            DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Картинка с переводом") },
                    enabled = canExportImage,
                    onClick = { shareMenu = false; onExportImage() },
                )
                DropdownMenuItem(text = { Text("Текст: оригинал и перевод") }, onClick = { shareMenu = false; onShareText() })
            }
        }
    }
}

/** Original and translation, paragraph by paragraph: what "share" and "export text" produce. */
fun shareableText(result: io.github.bropines.lens.protocol.ScanResult): String =
    result.paragraphs.joinToString("\n\n") { p ->
        listOfNotNull(p.text, p.translation?.text).joinToString("\n")
    }

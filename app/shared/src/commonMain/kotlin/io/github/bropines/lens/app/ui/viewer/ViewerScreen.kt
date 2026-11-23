package io.github.bropines.lens.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.platform.encodePng
import io.github.bropines.lens.app.ui.components.LanguageBar
import io.github.bropines.lens.gtranslate.GoogleTranslator
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.rememberTextSelectionState
import io.github.bropines.lens.overlay.renderTranslatedBitmap
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Scrim = Color.Black.copy(alpha = 0.6f)
private val FastIn = fadeIn(tween(120))
private val FastOut = fadeOut(tween(90))

@Composable
fun ViewerScreen(
    vm: ViewerViewModel,
    services: PlatformServices,
    translator: GoogleTranslator,
    onBack: () -> Unit,
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
    var quickTranslate by remember { mutableStateOf<String?>(null) }
    var zoomed by remember { mutableStateOf(false) }
    val renders = remember { mutableStateMapOf<Int, PageRender>() }
    val scope = rememberCoroutineScope()
    val highlight = MaterialTheme.colorScheme.primary
    fun styleFor(scan: ScanResult?): OverlayStyle {
        val manga = when (settings.readingLayout) {
            ReadingLayout.InPlace -> false
            ReadingLayout.Reflow -> true
            // Vertical source text means a comic page: bubbles want reflow, not per-column fitting.
            ReadingLayout.Auto -> scan?.paragraphs?.any { it.writingDirection == WritingDirection.TopToBottom } == true
        }
        // Lens misses tiny side columns (a lone "でも……"), so a reflow that grows far
        // runs over text it was never told about; grow a little less than the preset.
        val base = if (manga) OverlayStyle.Manga.copy(reflowGrowth = 1.25f) else OverlayStyle.Chromium
        return base.copy(highlightColor = highlight)
    }

    val selection = rememberTextSelectionState()

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            zoomed = false
            selection.clear()
            vm.onPageVisible(it)
        }
    }

    val current = pages[pager.currentPage]
    val result = (current as? PageState.Ready)?.result
    val style = styleFor(result)

    fun exportImage() {
        val render = renders[pager.currentPage] ?: return
        val overlay = render.overlay ?: return
        val scan = result ?: return
        val name = items[pager.currentPage].name.substringBeforeLast('.') + "-translated.png"
        scope.launch {
            val png = withContext(Dispatchers.Default) { renderTranslatedBitmap(render.bitmap, scan, style, overlay).encodePng() }
            services.shareImage(png, name)
        }
    }

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
                style = styleFor((pages[index] as? PageState.Ready)?.result),
                onTap = { chrome = !chrome },
                onParagraphTap = { if (index == pager.currentPage) tappedParagraph = it },
                onZoomed = { if (index == pager.currentPage) zoomed = it },
                onRender = { render -> if (render == null) renders.remove(index) else renders[index] = render },
                selection = if (index == pager.currentPage) selection else null,
            )
        }

        // Lens-style actions right above the selected text.
        val selectedText = selection.selectedText
        val bounds = selection.boundsInView
        if (mode == OverlayMode.TextBoxes && selectedText != null && bounds != null && !selection.isDragging) {
            SelectionActions(
                bounds = bounds,
                onCopy = { services.copyText(selectedText); selection.clear() },
                onTranslate = { quickTranslate = selectedText },
                onSelectAll = { result?.let(selection::selectAll) },
                onShare = { services.shareText(selectedText) },
            )
        }

        AnimatedVisibility(
            chrome,
            enter = FastIn + slideInVertically(tween(160)) { -it / 3 },
            exit = FastOut,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                title = items[pager.currentPage].name,
                counter = if (items.size > 1) "${pager.currentPage + 1} / ${items.size}" else null,
                source = settings.sourceLanguage,
                target = settings.targetLanguage,
                onSource = vm::setSource,
                onTarget = vm::setTarget,
                onBack = onBack,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanStatus(current, onRetry = { vm.retry(pager.currentPage) })
            AnimatedVisibility(chrome, enter = FastIn + slideInVertically(tween(160)) { it / 3 }, exit = FastOut + slideOutVertically(tween(120)) { it / 3 }) {
                ViewerBottomPanel(
                    mode = mode,
                    onMode = { mode = it },
                    result = result,
                    canExportImage = result != null && renders[pager.currentPage]?.overlay != null,
                    onCopyOriginal = { result?.let { services.copyText(it.readableText()) } },
                    onCopyTranslation = { result?.translation?.let(services::copyText) },
                    onShowText = { showText = true },
                    onShareText = { result?.let { services.shareText(shareableText(it)) } },
                    onExportImage = ::exportImage,
                    onSelectAll = { result?.let(selection::selectAll) },
                )
            }
        }
    }

    val paragraph = tappedParagraph?.let { result?.paragraphs?.getOrNull(it) }
    if (paragraph != null) {
        ParagraphSheet(
            paragraph = paragraph,
            services = services,
            onDismiss = { tappedParagraph = null },
            onTranslateWithGoogle = { tappedParagraph = null; quickTranslate = it },
        )
    }
    if (showText && result != null) {
        TextSheet(
            result = result,
            services = services,
            onDismiss = { showText = false },
            onTranslateWithGoogle = { showText = false; quickTranslate = it },
        )
    }
    quickTranslate?.let { text ->
        QuickTranslateSheet(
            text = text,
            target = settings.targetLanguage,
            translator = translator,
            services = services,
            onDismiss = { quickTranslate = null },
            onOpenFull = { quickTranslate = null; onTranslateText(text) },
        )
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    counter: String?,
    source: String?,
    target: String,
    onSource: (String?) -> Unit,
    onTarget: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Scrim).statusBarsPadding().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                counter?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
        }
        LanguageBar(source, target, onSource, onTarget, modifier = Modifier.padding(start = 16.dp), onDark = true)
    }
}

/** The scan state of the visible page: a spinner, an error with retry, or nothing once done. */
@Composable
private fun ScanStatus(state: PageState?, onRetry: () -> Unit) {
    AnimatedVisibility(state !is PageState.Ready, enter = FastIn, exit = FastOut) {
        when (state) {
            is PageState.Failed -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Text(state.message, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    Button(onClick = onRetry) { Text("Повторить") }
                }
            }
            else -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text("Распознаю текст…", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ViewerBottomPanel(
    mode: OverlayMode,
    onMode: (OverlayMode) -> Unit,
    result: ScanResult?,
    canExportImage: Boolean,
    onCopyOriginal: () -> Unit,
    onCopyTranslation: () -> Unit,
    onShowText: () -> Unit,
    onShareText: () -> Unit,
    onExportImage: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val hasText = result != null && result.paragraphs.isNotEmpty()
    val hasTranslation = result?.translation != null
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = MaterialTheme.shapes.extraSmall.bottomStart, bottomEnd = MaterialTheme.shapes.extraSmall.bottomEnd),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // What is shown over the image: three labelled, connected choices.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                val modes = listOf(
                    Triple(OverlayMode.Translated, "Перевод", Icons.Rounded.Translate),
                    Triple(OverlayMode.TextBoxes, "Текст", Icons.Rounded.TextFields),
                    Triple(OverlayMode.Original, "Оригинал", Icons.Rounded.Image),
                )
                modes.forEachIndexed { index, (value, label, icon) ->
                    ModeButton(
                        label = label,
                        icon = icon,
                        checked = mode == value,
                        onClick = { onMode(value) },
                        position = index,
                        count = modes.size,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // What can be done with it, spelled out.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    OverlayMode.Translated -> {
                        ActionChip("Копировать перевод", enabled = hasTranslation, onClick = onCopyTranslation, modifier = Modifier.weight(1.4f))
                        ActionChip("Картинкой", enabled = canExportImage, onClick = onExportImage, modifier = Modifier.weight(1f))
                    }
                    OverlayMode.TextBoxes -> {
                        ActionChip("Выделить всё", enabled = hasText, onClick = onSelectAll, modifier = Modifier.weight(1.2f))
                        ActionChip("Копировать всё", enabled = hasText, onClick = onCopyOriginal, modifier = Modifier.weight(1.2f))
                    }
                    OverlayMode.Original -> {
                        ActionChip("Копировать оригинал", enabled = hasText, onClick = onCopyOriginal, modifier = Modifier.weight(1.4f))
                        ActionChip("Поделиться", enabled = hasText, onClick = onShareText, modifier = Modifier.weight(1f))
                    }
                }
                ActionChip("Весь текст", enabled = hasText, onClick = onShowText, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onClick: () -> Unit,
    position: Int,
    count: Int,
    modifier: Modifier,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        shapes = when (position) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }
}

/** Original and translation, paragraph by paragraph: what "share" and "export text" produce. */
fun shareableText(result: ScanResult): String =
    result.paragraphs.joinToString("\n\n") { p ->
        listOfNotNull(p.readableText(), p.translation?.text).joinToString("\n")
    }

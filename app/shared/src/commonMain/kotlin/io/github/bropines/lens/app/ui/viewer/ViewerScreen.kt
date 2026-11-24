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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledTonalIconToggleButton
import io.github.bropines.lens.app.ui.components.languageName
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import io.github.bropines.lens.protocol.Region
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.draw.clip
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
import io.github.bropines.lens.app.data.TextTranslator
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
    translator: TextTranslator,
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
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var overlayHidden by rememberSaveable { mutableStateOf(false) }
    val stale by vm.stale.collectAsState()
    var askAi by remember { mutableStateOf(false) }
    var lastRegion by remember { mutableStateOf<Region?>(null) }
    val searching by vm.searching.collectAsState()
    val searchError by vm.searchError.collectAsState()
    val objects by vm.objects.collectAsState()
    val rotations by vm.rotations.collectAsState()
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
        return base.withPrefs(settings.overlay).copy(highlightColor = highlight)
    }

    val selection = rememberTextSelectionState()

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            zoomed = false
            selection.clear()
            vm.onPageVisible(it)
        }
    }

    // In search mode, upload the visible page for search right away so its object dots show up.
    LaunchedEffect(searchMode, pager.currentPage) { if (searchMode) vm.prepareSearch(pager.currentPage) }

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
            // A zoomed page pans with one finger, and in search mode a finger draws; neither turns the page.
            userScrollEnabled = !zoomed && !searchMode,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            Box(Modifier.fillMaxSize()) {
                PageView(
                    item = items[index],
                    state = pages[index],
                    mode = if (overlayHidden) OverlayMode.Original else mode,
                    style = styleFor((pages[index] as? PageState.Ready)?.result),
                    onTap = { chrome = !chrome },
                    onParagraphTap = { if (index == pager.currentPage) tappedParagraph = it },
                    onZoomed = { if (index == pager.currentPage) zoomed = it },
                    onRender = { render -> if (render == null) renders.remove(index) else renders[index] = render },
                    selection = if (index == pager.currentPage) selection else null,
                    rotation = rotations[items[index].key] ?: 0,
                )
                val render = renders[index]
                if (searchMode && index == pager.currentPage && render != null) {
                    SearchLayer(
                        image = render.bitmap,
                        objects = objects[index].orEmpty().map { Tappable(it.id, it.box) },
                        onRegion = { rect ->
                            val region = Region(rect.left, rect.top, rect.width, rect.height)
                            lastRegion = region
                            vm.visualSearch(index, services::openUrl) { it.searchRegionUrl(region) }
                        },
                        onObject = { obj -> vm.visualSearch(index, services::openUrl) { it.searchObjectUrl(obj.id) } },
                    )
                }
            }
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
                onSearch = { services.openUrl(vm.searchTextUrl(selectedText)) },
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
                onBack = onBack,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanStatus(
                current,
                refreshingTo = if (pager.currentPage in stale) languageName(settings.targetLanguage) else null,
                onRetry = { vm.retry(pager.currentPage) },
            )
            SearchStatus(searching, searchError, onDismiss = vm::dismissSearchError)
            AnimatedVisibility(chrome, enter = FastIn + slideInVertically(tween(160)) { it / 3 }, exit = FastOut + slideOutVertically(tween(120)) { it / 3 }) {
                Column {
                // Thumb row: language pair on the left, the eye on the right, right above the panel.
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguageBar(
                        settings.sourceLanguage, settings.targetLanguage, vm::setSource, vm::setTarget,
                        modifier = Modifier.weight(1f),
                        onDark = true,
                    )
                    // A sideways photo reads badly: turn it upright, a quarter per tap, and it is read again.
                    FilledTonalIconButton(
                        onClick = {
                            selection.clear()
                            tappedParagraph = null
                            lastRegion = null
                            vm.rotate(pager.currentPage)
                        },
                        modifier = Modifier.padding(start = 12.dp).size(56.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.RotateRight,
                            contentDescription = stringResource(Res.string.viewer_rotate),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    FilledTonalIconToggleButton(
                        checked = overlayHidden,
                        onCheckedChange = { overlayHidden = !overlayHidden; selection.clear() },
                        modifier = Modifier.padding(start = 12.dp).size(56.dp),
                    ) {
                        Icon(
                            if (overlayHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(if (overlayHidden) Res.string.viewer_show_translation else Res.string.viewer_hide_translation),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                ViewerBottomPanel(
                    mode = mode,
                    onMode = { mode = it; searchMode = false },
                    searchMode = searchMode,
                    onSearchMode = { searchMode = true; selection.clear() },
                    onSearchWhole = { vm.visualSearch(pager.currentPage, services::openUrl) { it.searchImageUrl() } },
                    onAskAi = { askAi = true },
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
    }

    if (askAi) {
        AskAiDialog(
            hasRegion = lastRegion != null,
            onDismiss = { askAi = false },
            onAsk = { question, useRegion ->
                askAi = false
                val region = if (useRegion) lastRegion else null
                vm.visualSearch(pager.currentPage, services::openUrl) { it.askAiUrl(question, region) }
            },
        )
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
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Scrim).statusBarsPadding().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.back), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                counter?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** The scan state of the visible page: a spinner, an error with retry, or nothing once done. */
@Composable
private fun ScanStatus(state: PageState?, refreshingTo: String?, onRetry: () -> Unit) {
    // Hold the language name through the fade-out, when refreshingTo is already null.
    var language by remember { mutableStateOf("") }
    if (refreshingTo != null) language = refreshingTo
    AnimatedVisibility(state !is PageState.Ready || refreshingTo != null, enter = FastIn, exit = FastOut) {
        when (state) {
            is PageState.Ready -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text(stringResource(Res.string.viewer_translating_to, language), Modifier.padding(start = 8.dp))
                }
            }
            is PageState.Failed -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Text(state.message.asString(), maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    Button(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
                }
            }
            else -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text(stringResource(Res.string.viewer_recognising), Modifier.padding(start = 8.dp))
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
    searchMode: Boolean,
    onSearchMode: () -> Unit,
    onSearchWhole: () -> Unit,
    onAskAi: () -> Unit,
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
                // The original is the eye in the top bar, not a mode: it works over any of these.
                val modes = listOf(
                    Triple(OverlayMode.Translated, stringResource(Res.string.translation), Icons.Rounded.Translate),
                    Triple(OverlayMode.TextBoxes, stringResource(Res.string.tab_text), Icons.Rounded.TextFields),
                )
                modes.forEachIndexed { index, (value, label, icon) ->
                    ModeButton(
                        label = label,
                        icon = icon,
                        checked = !searchMode && mode == value,
                        onClick = { onMode(value) },
                        position = index,
                        count = modes.size + 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                ModeButton(
                    label = stringResource(Res.string.viewer_mode_search),
                    icon = Icons.Rounded.ImageSearch,
                    checked = searchMode,
                    onClick = onSearchMode,
                    position = modes.size,
                    count = modes.size + 1,
                    modifier = Modifier.weight(1f),
                )
            }
            if (searchMode) {
                Text(
                    stringResource(Res.string.viewer_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            // What can be done with it, spelled out.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cell = Modifier.weight(1f)
                if (searchMode) {
                    ActionChip(Icons.Rounded.ImageSearch, stringResource(Res.string.viewer_whole_image), true, onSearchWhole, cell)
                    ActionChip(Icons.Rounded.AutoAwesome, stringResource(Res.string.viewer_ask_ai), true, onAskAi, cell)
                    ActionChip(Icons.Rounded.Notes, stringResource(Res.string.viewer_all_text), hasText, onShowText, cell)
                    return@Row
                }
                when (mode) {
                    OverlayMode.Translated -> {
                        ActionChip(Icons.Rounded.ContentCopy, stringResource(Res.string.copy), hasTranslation, onCopyTranslation, cell)
                        ActionChip(Icons.Rounded.AddPhotoAlternate, stringResource(Res.string.viewer_as_image), canExportImage, onExportImage, cell)
                        ActionChip(Icons.Rounded.Share, stringResource(Res.string.share), hasText, onShareText, cell)
                    }
                    OverlayMode.TextBoxes -> {
                        ActionChip(Icons.Rounded.SelectAll, stringResource(Res.string.select_all), hasText, onSelectAll, cell)
                        ActionChip(Icons.Rounded.ContentCopy, stringResource(Res.string.copy), hasText, onCopyOriginal, cell)
                        ActionChip(Icons.Rounded.Share, stringResource(Res.string.share), hasText, onShareText, cell)
                    }
                    OverlayMode.Original -> {
                        ActionChip(Icons.Rounded.ContentCopy, stringResource(Res.string.copy), hasText, onCopyOriginal, cell)
                        ActionChip(Icons.Rounded.Share, stringResource(Res.string.share), hasText, onShareText, cell)
                    }
                }
                ActionChip(Icons.Rounded.Notes, stringResource(Res.string.viewer_all_text), hasText, onShowText, cell)
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        // Icon over label: four modes fit a phone's width without truncating.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A visual search on its way to the browser, or why it did not get there. */
@Composable
private fun SearchStatus(searching: Boolean, error: UiText?, onDismiss: () -> Unit) {
    AnimatedVisibility(searching || error != null, enter = FastIn, exit = FastOut) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (error != null) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Text(error.asString(), Modifier.padding(start = 8.dp).weight(1f, fill = false), maxLines = 2)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.hide)) }
                } else {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text(stringResource(Res.string.viewer_searching), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** A question for Google's AI Mode about the picture, or about the area drawn last. */
@Composable
private fun AskAiDialog(hasRegion: Boolean, onDismiss: () -> Unit, onAsk: (String, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    var useRegion by remember { mutableStateOf(hasRegion) }
    val defaultQuestion = stringResource(Res.string.ask_ai_default_question)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
        title = { Text(stringResource(Res.string.ask_ai_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text(stringResource(Res.string.ask_ai_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasRegion) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useRegion, onCheckedChange = { useRegion = it })
                        Text(stringResource(Res.string.ask_ai_region_only))
                    }
                }
                Text(
                    stringResource(Res.string.ask_ai_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAsk(question.ifBlank { defaultQuestion }, useRegion) }) { Text(stringResource(Res.string.ask_ai_ask)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** An action as Lens shows them: an icon in a tonal pill with a short label underneath. */
@Composable
private fun ActionChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 36.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.5f), MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp)) }
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/** Original and translation, paragraph by paragraph: what "share" and "export text" produce. */
fun shareableText(result: ScanResult): String =
    result.paragraphs.joinToString("\n\n") { p ->
        listOfNotNull(p.readableText(), p.translation?.text).joinToString("\n")
    }

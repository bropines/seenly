package io.github.bropines.lens.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.rounded.VisibilityOff
import io.github.bropines.lens.app.ui.components.languageName
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import io.github.bropines.lens.protocol.Region
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.bropines.lens.app.data.GalleryOrigin
import io.github.bropines.lens.app.data.ViewMode
import io.github.bropines.lens.app.data.sameLanguage
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.platform.encodePng
import io.github.bropines.lens.app.ui.components.LanguageBar
import io.github.bropines.lens.app.data.TextTranslator
import io.github.bropines.lens.app.ui.gallery.HeroFlight
import io.github.bropines.lens.app.ui.gallery.LocalHeroRegistry
import io.github.bropines.lens.app.ui.gallery.PageBadge
import io.github.bropines.lens.app.ui.gallery.dragProgress
import io.github.bropines.lens.app.ui.gallery.dragWindow
import io.github.bropines.lens.app.ui.gallery.heroFrame
import io.github.bropines.lens.app.ui.gallery.scaledAboutCenter
import io.github.bropines.lens.overlay.OverlayMode
import io.github.bropines.lens.overlay.OverlayStyle
import io.github.bropines.lens.overlay.rememberTextSelectionState
import io.github.bropines.lens.overlay.renderTranslatedBitmap
import io.github.bropines.lens.overlay.wordCount
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.WritingDirection
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.saveImageToGallery
import io.github.vinceglb.filekit.readBytes
import io.github.bropines.lens.app.ui.camera.CodeCard
import io.github.bropines.lens.app.ui.camera.ScannedCode
import io.github.bropines.lens.app.ui.camera.readCode
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Scrim = Color.Black.copy(alpha = 0.6f)
private val FastIn = fadeIn(tween(120))
private val FastOut = fadeOut(tween(90))

/** How far down, as a share of the screen's height, a pull has to go to put the page away when it is let go. */
private const val PULL_AWAY = 0.16f

/** The pull that takes the page as far from itself as it gets: smaller, the dark behind it nearly gone. */
private const val PULL_TRAVEL = 0.55f

/** A flick down this fast, in dp per second, puts the page away however short it was. */
private const val FLICK_AWAY_DP = 420f

/** How long the eye has to be held before it peeks rather than toggles. */
private const val PEEK_AFTER_MS = 200L

/**
 * The viewer: the gallery's pictures one at a time, each with its translation
 * drawn over it.
 *
 * Swipe sideways between pictures; pinch or double-tap to zoom; tap for the
 * controls. Pull a picture down to put it away: it follows the finger and
 * goes back into the thumbnail it came from (the gallery's own grid, or the
 * history grid beneath), or the viewer closes. Hold a picture for the quick
 * actions, hold the eye to peek at the original.
 */
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
    val startMode = vm.gallery.startMode
    var mode by rememberSaveable { mutableStateOf(if (startMode == ViewMode.Text) OverlayMode.TextBoxes else OverlayMode.Translated) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var tappedParagraph by remember { mutableStateOf<Int?>(null) }
    var quickTranslate by remember { mutableStateOf<String?>(null) }
    var zoomed by remember { mutableStateOf(false) }
    var searchMode by rememberSaveable { mutableStateOf(startMode == ViewMode.Search || startMode == ViewMode.AskAi) }
    var overlayHidden by rememberSaveable { mutableStateOf(false) }
    val stale by vm.stale.collectAsState()
    val failed by vm.failed.collectAsState()
    // Codes found in the pictures, by page key; a page without one maps to null once looked at.
    val codes = remember { mutableStateMapOf<String, ScannedCode?>() }
    var dismissedCodes by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pager.settledPage) {
        val item = items.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (item.key in codes) return@LaunchedEffect
        codes[item.key] = runCatching { readCode(item.file.readBytes()) }.getOrNull()
    }
    // Opened to search (shared to "Search", or the camera's mode): the search itself opens, not just the dots.
    var autoSearched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startMode == ViewMode.Search && !autoSearched) {
            autoSearched = true
            vm.visualSearch(pager.currentPage, services::openUrl) { it.searchImageUrl() }
        }
    }
    // Shared with "Ask AI": the question comes first.
    var askAi by rememberSaveable { mutableStateOf(startMode == ViewMode.AskAi) }
    var lastRegion by remember { mutableStateOf<Region?>(null) }
    val searching by vm.searching.collectAsState()
    val searchError by vm.searchError.collectAsState()
    val objects by vm.objects.collectAsState()
    val rotations by vm.rotations.collectAsState()
    val renders = remember { mutableStateMapOf<Int, PageRender>() }
    val scope = rememberCoroutineScope()
    val highlight = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val registry = LocalHeroRegistry.current
    val goBack by rememberUpdatedState(onBack)
    fun styleFor(scan: ScanResult?): OverlayStyle = settings.overlayStyle(scan).copy(highlightColor = highlight)

    val selection = rememberTextSelectionState()

    // The page in flight, and what the page in front says of itself.
    val flight = remember { HeroFlight() }
    val handle = remember { PageHandle() }
    val leave = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // A gallery of several pictures has a grid of its own to go back to; history's is the grid beneath.
    val ownGrid = items.size > 1 && vm.gallery.origin != GalleryOrigin.History
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var gridMade by remember { mutableStateOf(showGrid) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = vm.gallery.startIndex)
    var opened by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var landing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var peeking by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var armed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val clip = remember { Path() }
    val cellCorner = with(density) { GridCellCorner.toPx() }
    val flickAway = with(density) { FLICK_AWAY_DP.dp.toPx() }

    /** Where [index]'s thumbnail is on screen, in the viewer's own pixels, if one is. */
    fun thumbOf(index: Int): Rect? = registry?.boundsOf(items[index].key)?.translate(-origin.x, -origin.y)

    /** Where the picture of [key] rests, once the page showing it has said. */
    suspend fun restOf(key: String): Rect =
        snapshotFlow { if (handle.owner == key) handle.picture else Rect.Zero }.first { !it.isEmpty }

    LaunchedEffect(Unit) {
        flight.corner = cellCorner
        runCatching { focus.requestFocus() }
        if (opened) {
            // Restored, not opened: everything is where it was left.
            if (showGrid) flight.clear() else flight.settle()
            return@LaunchedEffect
        }
        val index = pager.currentPage
        val key = items[index].key
        val rest = restOf(key)
        // Only now is the thumbnail taken out of its cell: in the same frame the page appears in its place.
        val from = if (ownGrid) null else thumbOf(index)
        registry?.shown = key
        if (from != null) flight.open(from, rest) else flight.appear(rest)
        opened = true
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            zoomed = false
            selection.clear()
            if (opened && !showGrid && !leaving) registry?.shown = items[it].key
        }
    }
    // A page the strip is being dragged past is looked at, not read: only where it stops.
    LaunchedEffect(pager, scrubbing) {
        if (!scrubbing) snapshotFlow { pager.currentPage }.collect { vm.onPageVisible(it) }
    }
    // The grid, once made, follows the reader, so the page finds its cell when it is put away.
    LaunchedEffect(gridMade) {
        if (!gridMade) return@LaunchedEffect
        snapshotFlow { pager.currentPage }.collect { page ->
            if (!showGrid && gridState.layoutInfo.visibleItemsInfo.none { it.index == page }) gridState.scrollToItem(page)
        }
    }
    if (registry != null) {
        // What lies beneath need not be drawn while it cannot be seen.
        LaunchedEffect(registry) {
            snapshotFlow {
                !leaving && leave.value >= 0.999f &&
                    ((ownGrid && gridMade) || (!flight.active && flight.shownBackdrop >= 0.999f && flight.shownPresence >= 0.999f))
            }.collect { registry.covered = it }
        }
        DisposableEffect(registry) {
            onDispose {
                registry.covered = false
                registry.shown = null
            }
        }
    }

    // In search mode, upload the visible page for search right away so its object dots show up.
    LaunchedEffect(searchMode, pager.currentPage) { if (searchMode) vm.prepareSearch(pager.currentPage) }

    val front = pager.currentPage
    val current = pages[front]
    val shownLanguages = remember(current, settings.targetLanguage, settings.sourceLanguage) {
        val result = (current as? PageState.Ready)?.result
        val translated = result?.paragraphs?.firstNotNullOfOrNull { it.translation }
        val detected = result?.language ?: translated?.sourceLanguage
        val into = translated?.targetLanguage?.takeIf { !sameLanguage(it, settings.targetLanguage) }
        detected to into
    }
    val result = (current as? PageState.Ready)?.result
    val style = styleFor(result)
    // Holding the eye shows the other of the two for as long as it is held.
    val original = overlayHidden != peeking

    /** Closes the viewer: the picture goes back into the thumbnail beneath it, or everything steps back. */
    fun close(velocity: Offset = Offset.Zero) {
        if (leaving) return
        leaving = true
        scope.launch {
            if (showGrid || ownGrid) {
                leave.animateTo(0f, tween(150))
            } else {
                handle.resetZoom()
                landing = true
                val to = thumbOf(pager.currentPage)
                if (to != null) flight.land(handle.picture, to, velocity) else flight.vanish(handle.picture, velocity)
            }
            registry?.shown = null
            goBack()
        }
    }

    /** Puts the page in front away into its cell of the gallery's own grid. */
    fun toGrid(velocity: Offset = Offset.Zero) {
        if (!ownGrid || showGrid || leaving) return
        scope.launch {
            gridMade = true
            handle.resetZoom()
            landing = true
            // The grid may have been made this very moment: give its cells a few frames to be laid out.
            var to = thumbOf(pager.currentPage)
            var waited = 0
            while (to == null && waited++ < 4) {
                androidx.compose.runtime.withFrameNanos { }
                to = thumbOf(pager.currentPage)
            }
            if (to != null) flight.land(handle.picture, to, velocity) else flight.vanish(handle.picture, velocity)
            showGrid = true
            registry?.shown = null
            flight.clear()
            landing = false
        }
    }

    /** Out of the grid: page [index] flies out of its cell. */
    fun fromGrid(index: Int) {
        if (flight.flying || leaving) return
        scope.launch {
            val key = items[index].key
            val from = thumbOf(index)
            pager.scrollToPage(index)
            val rest = restOf(key)
            registry?.shown = key
            showGrid = false
            if (from != null) flight.open(from, rest) else flight.appear(rest)
        }
    }

    fun turn(by: Int) {
        val target = (pager.currentPage + by).coerceIn(0, items.lastIndex)
        if (target != pager.currentPage && !showGrid) scope.launch { pager.animateScrollToPage(target) }
    }

    val gestures = remember {
        PageGestures(
            canDismiss = {
                !zoomed && !searchMode && !showGrid && !leaving && !flight.flying && !handle.strip &&
                    !pager.isScrollInProgress && selection.selection == null
            },
            onDismissStart = { at ->
                grab = at
                armed = false
                if (ownGrid) gridMade = true
            },
            onDismissDrag = { total ->
                val travel = viewport.height * PULL_TRAVEL
                flight.hold(dragWindow(handle.picture, grab, total, travel), 1f - 0.85f * dragProgress(total, travel))
                val far = total.y > viewport.height * PULL_AWAY
                if (far != armed) {
                    armed = far
                    haptics.performHapticFeedback(if (far) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                }
            },
            onDismissEnd = { velocity ->
                when {
                    !armed && velocity.y < flickAway -> scope.launch { flight.release(handle.picture, velocity) }
                    ownGrid -> toGrid(velocity)
                    else -> close(velocity)
                }
            },
            onDismissCancel = { scope.launch { flight.release(handle.picture) } },
            canHold = { !searchMode && mode != OverlayMode.TextBoxes && !showGrid && !leaving && !flight.active },
            onHold = { at ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menuAt = at
            },
        )
    }

    // Back, by button or by the system's gesture. While the gesture is under way the page draws back
    // with it (or, where the whole viewer leaves, the viewer does); let go, it leaves; taken back, it returns.
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = back,
        onBackCancelled = {
            scope.launch { if (showGrid || ownGrid) leave.animateTo(1f, spring()) else flight.release(handle.picture) }
        },
        onBackCompleted = { close() },
    )
    LaunchedEffect(back) {
        snapshotFlow { (back.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress }
            .collect { progress ->
                when {
                    progress == null || leaving -> Unit
                    showGrid || ownGrid -> leave.snapTo(1f - 0.3f * progress)
                    !flight.flying -> flight.hold(handle.picture.scaledAboutCenter(1f - 0.12f * progress), 1f - 0.4f * progress)
                }
            }
    }

    /** The page in front with its translation drawn in, as a PNG and a name for it. */
    suspend fun translatedPng(): Pair<ByteArray, String>? {
        val index = pager.currentPage
        val render = renders[index] ?: return null
        val overlay = render.overlay ?: return null
        val scan = (pages[index] as? PageState.Ready)?.result ?: return null
        val name = items[index].name.substringBeforeLast('.') + "-" + settings.targetLanguage + ".png"
        exporting = true
        try {
            val png = withContext(Dispatchers.Default) { renderTranslatedBitmap(render.bitmap, scan, styleFor(scan), overlay).encodePng() }
            return png to name
        } finally {
            exporting = false
        }
    }

    fun shareImage(withText: Boolean) {
        scope.launch {
            val (png, name) = translatedPng() ?: return@launch
            val text = result?.let(::shareableText)
            if (withText && text != null) services.shareImage(png, name, text) else services.shareImage(png, name)
        }
    }

    fun saveImage() {
        scope.launch {
            val (png, name) = translatedPng() ?: return@launch
            val saved = runCatching { FileKit.saveImageToGallery(png, name).getOrThrow() }
            snackbar.showSnackbar(
                if (saved.isSuccess) {
                    getString(Res.string.viewer_saved)
                } else {
                    getString(Res.string.viewer_save_failed, saved.exceptionOrNull()?.message.orEmpty())
                },
            )
        }
    }

    val canExportImage = result != null && renders[front]?.overlay != null

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                viewport = it.size
            }
            .graphicsLayer {
                alpha = leave.value
                val step = 0.95f + 0.05f * leave.value
                scaleX = step
                scaleY = step
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.PageDown -> turn(1)
                    Key.DirectionLeft, Key.PageUp -> turn(-1)
                    Key.Escape -> close()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        if (ownGrid && gridMade) {
            val translated by vm.translated.collectAsState()
            val done by vm.done.collectAsState()
            fun badgeOf(index: Int): PageBadge? = when {
                index in failed -> PageBadge.Failed
                index in done || items[index].key in translated -> PageBadge.Translated
                else -> null
            }
            Box(
                Modifier
                    .fillMaxSize()
                    // Above the page only when it is the grid's turn; beneath it while a page flies or is pulled.
                    .zIndex(if (showGrid && !flight.active) 1f else 0f)
                    .graphicsLayer { alpha = if (showGrid || flight.shownBackdrop < 0.999f) 1f else 0f },
            ) {
                GalleryGrid(
                    title = vm.gallery.title,
                    items = items,
                    current = front,
                    translatedCount = items.indices.count { badgeOf(it) == PageBadge.Translated },
                    badge = ::badgeOf,
                    state = gridState,
                    registry = registry,
                    onOpen = ::fromGrid,
                    onClose = { close() },
                )
            }
        }

        // The dark behind the page: thinned while the page is pulled or in flight, to show what it goes back to.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (showGrid && !flight.active) 0f else flight.shownBackdrop }.background(Color.Black))

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items[it].key },
            // A zoomed page pans with one finger, and in search mode a finger draws; neither turns the page.
            userScrollEnabled = !zoomed && !searchMode && !showGrid && !flight.active,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (showGrid && !flight.active) 0f else flight.shownPresence
                    val picture = handle.picture
                    if (flight.active && !picture.isEmpty && size.width > 0f && size.height > 0f) {
                        val frame = heroFrame(flight.shownWindow, picture)
                        transformOrigin = TransformOrigin(picture.center.x / size.width, picture.center.y / size.height)
                        scaleX = frame.scale
                        scaleY = frame.scale
                        translationX = frame.translation.x
                        translationY = frame.translation.y
                    }
                }
                .drawWithContent {
                    val picture = handle.picture
                    if (flight.active && !picture.isEmpty) {
                        val frame = heroFrame(flight.shownWindow, picture, flight.shownCorner(picture))
                        clip.rewind()
                        clip.addRoundRect(RoundRect(frame.clip, CornerRadius(frame.corner)))
                        clipPath(clip) { this@drawWithContent.drawContent() }
                    } else {
                        drawContent()
                    }
                },
        ) { index ->
            val inFront = index == pager.currentPage
            Box(Modifier.fillMaxSize()) {
                PageView(
                    item = items[index],
                    state = pages[index],
                    // Going back into its thumbnail a picture is itself again: the thumbnail has no translation on it.
                    mode = if (original || landing) OverlayMode.Original else mode,
                    style = styleFor((pages[index] as? PageState.Ready)?.result),
                    onTap = { chrome = !chrome },
                    onParagraphTap = { if (index == pager.currentPage) tappedParagraph = it },
                    onZoomed = { if (index == pager.currentPage) zoomed = it },
                    onRender = { render -> if (render == null) renders.remove(index) else renders[index] = render },
                    selection = if (inFront) selection else null,
                    rotation = rotations[items[index].key] ?: 0,
                    handle = if (inFront) handle else null,
                    gestures = if (inFront) gestures else null,
                    light = scrubbing,
                    scanning = inFront && !scrubbing && pages[index] is PageState.Loading,
                )
                val render = renders[index]
                if (searchMode && inFront && render != null) {
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

        val quiet = !showGrid && !flight.active && !leaving

        // Lens-style actions right above the selected text.
        val selectedText = selection.selectedText
        val bounds = selection.boundsInView
        if (quiet && mode == OverlayMode.TextBoxes && selectedText != null && bounds != null && !selection.isDragging) {
            // Everything selected is copied as it is read, not as Lens happened to list it.
            val everything = result != null && selection.selection?.count == result.wordCount
            SelectionActions(
                // A strip's selection is measured in the strip, which is scrolled under the screen.
                bounds = bounds.translate(0f, -handle.scrollY),
                onCopy = { services.copyText(if (everything) result.readableText() else selectedText); selection.clear() },
                onTranslate = { quickTranslate = selectedText },
                onSelectAll = { result?.let(selection::selectAll) },
                onShare = { services.shareText(selectedText) },
                onSearch = { services.openUrl(vm.searchTextUrl(selectedText)) },
            )
        }

        QuickMenu(
            at = menuAt,
            hasText = result != null && result.paragraphs.isNotEmpty(),
            hasTranslation = result?.translation != null,
            canExport = canExportImage,
            showingOriginal = overlayHidden,
            onDismiss = { menuAt = null },
            onCopyTranslation = { result?.translatedText()?.let(services::copyText) },
            onCopyOriginal = { result?.let { services.copyText(it.readableText()) } },
            onShare = { sharing = true },
            onSave = ::saveImage,
            onRescan = { vm.rescan(pager.currentPage) },
            onToggleOriginal = { overlayHidden = !overlayHidden; selection.clear() },
        )

        AnimatedVisibility(
            chrome && quiet,
            enter = FastIn + slideInVertically(tween(160)) { -it / 3 },
            exit = FastOut,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                title = items[front].name,
                counter = if (items.size > 1) "${front + 1} / ${items.size}" else null,
                onBack = { close() },
                onGrid = if (ownGrid) ({ toGrid() }) else null,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            if (quiet) {
                // With the controls away, a turned page says which one it is, and then is quiet again.
                PageNumber(front, items.size, visible = !chrome && items.size > 1)
                ScanStatus(
                    current,
                    refreshingTo = if (front in stale) languageName(settings.targetLanguage) else null,
                    onRetry = { vm.retry(front) },
                    retryOnline = !settings.onDevice.always,
                )
                BusyStatus(exporting, stringResource(Res.string.viewer_preparing_image))
                SearchStatus(searching, searchError, onDismiss = vm::dismissSearchError)
                // A QR code or barcode in the picture, read on the device: the same card as the camera's.
                val code = codes[items[front].key]?.takeIf { items[front].key !in dismissedCodes }
                AnimatedVisibility(chrome && code != null, enter = FastIn, exit = FastOut) {
                    code?.let { found ->
                        CodeCard(found, services, onDismiss = { dismissedCodes = dismissedCodes + items[front].key })
                    }
                }
            }
            // Folded, the controls are one row over the picture: languages, rotate, the eye.
            val compact = settings.viewerCompact
            AnimatedVisibility(
                chrome && quiet,
                enter = FastIn + slideInVertically(tween(160)) { it / 3 },
                exit = FastOut + slideOutVertically(tween(120)) { it / 3 },
            ) {
                Column {
                    // Darkened towards the panel: the controls over the picture read on a white page too.
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .padding(top = 20.dp)
                            .then(if (compact) Modifier.navigationBarsPadding() else Modifier),
                    ) {
                        // The picture's own buttons stand in a column at the right edge, the eye lowest,
                        // nearest the thumb: the language pair below gets the whole width.
                        Column(
                            Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Fold the panel away, or bring it back; the choice is remembered.
                            FilledTonalIconButton(onClick = { vm.setCompact(!compact) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (compact) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = stringResource(if (compact) Res.string.viewer_panel_expand else Res.string.viewer_panel_collapse),
                                )
                            }
                            // A sideways photo reads badly: turn it upright, a quarter per tap, and it is read again.
                            FilledTonalIconButton(
                                onClick = {
                                    selection.clear()
                                    tappedParagraph = null
                                    lastRegion = null
                                    vm.rotate(pager.currentPage)
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.RotateRight,
                                    contentDescription = stringResource(Res.string.viewer_rotate),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            PeekButton(
                                hidden = overlayHidden,
                                onToggle = { overlayHidden = !overlayHidden; selection.clear() },
                                onPeek = { peeking = it },
                                size = 56.dp,
                            )
                        }
                        AnimatedVisibility(!compact && items.size > 1) {
                            Filmstrip(
                                items = items,
                                current = front,
                                failed = failed,
                                onPick = { scope.launch { pager.animateScrollToPage(it) } },
                                onScrub = { scope.launch { pager.scrollToPage(it) } },
                                onScrubbing = { scrubbing = it },
                            )
                        }
                        // Thumb row: the language pair, across the whole width, right above the panel.
                        LanguageBar(
                            settings.sourceLanguage, settings.targetLanguage, vm::setSource, vm::setTarget,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                            onDark = true,
                            // What this page really is: the language Lens found, and the one it went into.
                            detected = shownLanguages.first,
                            actualTarget = shownLanguages.second,
                        )
                    }
                    AnimatedVisibility(!compact) {
                    ViewerBottomPanel(
                        mode = mode,
                        onMode = {
                            mode = it
                            searchMode = false
                            vm.rememberCameraMode(if (it == OverlayMode.TextBoxes) ViewMode.Text else ViewMode.Translate)
                        },
                        searchMode = searchMode,
                        onSearchMode = {
                            searchMode = true
                            selection.clear()
                            vm.rememberCameraMode(ViewMode.Search)
                        },
                        onSearchWhole = { vm.visualSearch(pager.currentPage, services::openUrl) { it.searchImageUrl() } },
                        onAskAi = { askAi = true },
                        result = result,
                        canExportImage = canExportImage,
                        onCopyOriginal = { result?.let { services.copyText(it.readableText()) } },
                        onCopyTranslation = { result?.translatedText()?.let(services::copyText) },
                        onShowText = { showText = true },
                        onShareText = { result?.let { services.shareText(shareableText(it)) } },
                        onShare = { sharing = true },
                        onSaveImage = ::saveImage,
                        onSelectAll = { result?.let(selection::selectAll) },
                    )
                    }
                }
            }
        }

        // With every control away, the eye stays: small and see-through in the corner,
        // one tap from translation on or off, and held to peek, as in the panel.
        AnimatedVisibility(
            !chrome && quiet,
            enter = FastIn,
            exit = FastOut,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            PeekButton(
                hidden = overlayHidden,
                onToggle = { overlayHidden = !overlayHidden; selection.clear() },
                onPeek = { peeking = it },
                size = 44.dp,
                translucent = true,
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            )
        }
    }

    if (sharing) {
        ShareSheet(
            canExport = canExportImage,
            onDismiss = { sharing = false },
            onImage = { shareImage(withText = false) },
            onText = { result?.let { services.shareText(shareableText(it)) } },
            onBoth = { shareImage(withText = true) },
        )
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
    onGrid: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().background(Scrim).statusBarsPadding().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.back), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                counter?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
            if (onGrid != null) {
                IconButton(onClick = onGrid) {
                    Icon(Icons.Rounded.GridView, contentDescription = stringResource(Res.string.viewer_all_pages), tint = Color.White)
                }
            }
        }
    }
}

/**
 * The eye: a tap hides the translation or brings it back; held, it shows the
 * other of the two for as long as it is held, to compare with the original at
 * a glance.
 */
@Composable
private fun PeekButton(
    hidden: Boolean,
    onToggle: () -> Unit,
    onPeek: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    /** Over the bare picture: see-through until it is acting, so it hides little of the page. */
    translucent: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val toggle by rememberUpdatedState(onToggle)
    val peek by rememberUpdatedState(onPeek)
    val label = stringResource(if (hidden) Res.string.viewer_show_translation else Res.string.viewer_hide_translation)
    val hint = stringResource(Res.string.viewer_hold_to_peek)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    held -> MaterialTheme.colorScheme.primary
                    hidden -> MaterialTheme.colorScheme.primary.copy(alpha = if (translucent) 0.75f else 1f)
                    translucent -> Color.Black.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = label
                onClick(hint) { toggle(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    var lifted = false
                    val quick = withTimeoutOrNull(PEEK_AFTER_MS) {
                        lifted = waitForUpOrCancellation() != null
                        true
                    }
                    if (quick != null) {
                        if (lifted) toggle()
                    } else {
                        held = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        peek(true)
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            held = false
                            peek(false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (hidden != held) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = null,
            tint = when {
                hidden || held -> MaterialTheme.colorScheme.onPrimary
                translucent -> Color.White.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.size(size / 2),
        )
    }
}

/** "12 / 48", shown for a moment after the page turns while the controls are away. */
@Composable
private fun PageNumber(page: Int, count: Int, visible: Boolean) {
    var shown by remember { mutableStateOf(false) }
    // Not for the page the controls were hidden on: only for the ones turned to after.
    var last by remember { mutableStateOf(page) }
    LaunchedEffect(page, visible) {
        val turned = page != last
        last = page
        if (visible && turned) {
            shown = true
            delay(1300)
        }
        shown = false
    }
    AnimatedVisibility(shown && visible, enter = FastIn, exit = fadeOut(tween(260))) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.navigationBarsPadding().padding(12.dp)) {
            Text(
                "${page + 1} / $count",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** The scan state of the visible page: a spinner, an error with retry, a note on an on-device scan, or nothing once done. */
@Composable
private fun ScanStatus(state: PageState?, refreshingTo: String?, onRetry: () -> Unit, retryOnline: Boolean = true) {
    // Hold the language name through the fade-out, when refreshingTo is already null.
    var language by remember { mutableStateOf("") }
    if (refreshingTo != null) language = refreshingTo
    val offline = state is PageState.Ready && state.offline && refreshingTo == null
    // A page with nothing to read says so once, briefly: otherwise it looks as if nothing happened.
    val empty = state is PageState.Ready && state.result.paragraphs.isEmpty() && refreshingTo == null && !offline
    var emptyShown by remember { mutableStateOf(false) }
    LaunchedEffect(state, empty) {
        emptyShown = empty
        if (empty) {
            delay(2600)
            emptyShown = false
        }
    }
    AnimatedVisibility(state != null && (state !is PageState.Ready || refreshingTo != null || offline || (empty && emptyShown)), enter = FastIn, exit = FastOut) {
        when {
            // Scanned on the device: say so, since it reads and translates less well than Lens.
            offline -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(start = 16.dp, end = if (retryOnline) 8.dp else 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null)
                    Text(stringResource(Res.string.viewer_offline), maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    // Pointless when the user chose to stay on the device.
                    if (retryOnline) TextButton(onClick = onRetry) { Text(stringResource(Res.string.viewer_offline_retry)) }
                }
            }
            empty -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.SearchOff, contentDescription = null)
                    Text(stringResource(Res.string.viewer_no_text))
                }
            }
            state is PageState.Ready -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text(stringResource(Res.string.viewer_translating_to, language), Modifier.padding(start = 8.dp))
                }
            }
            state is PageState.Failed -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // What went wrong at a glance: no network, told to wait, or something else.
                    Icon(
                        when (state.kind) {
                            FailKind.Offline -> Icons.Rounded.WifiOff
                            FailKind.RateLimited -> Icons.Rounded.HourglassTop
                            FailKind.Other -> Icons.Rounded.ErrorOutline
                        },
                        contentDescription = null,
                    )
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

/** Something that takes a moment is under way (the translated picture being drawn for export). */
@Composable
private fun BusyStatus(busy: Boolean, label: String) {
    AnimatedVisibility(busy, enter = FastIn, exit = FastOut) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp))
                Text(label, Modifier.padding(start = 8.dp))
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
    onShare: () -> Unit,
    onSaveImage: () -> Unit,
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
                    ActionChip(Icons.AutoMirrored.Rounded.Notes, stringResource(Res.string.viewer_all_text), hasText, onShowText, cell)
                    return@Row
                }
                when (mode) {
                    OverlayMode.Translated -> {
                        ActionChip(Icons.Rounded.ContentCopy, stringResource(Res.string.copy), hasTranslation, onCopyTranslation, cell)
                        // To the gallery; sending it somewhere is under Share, with the text or without.
                        ActionChip(Icons.Rounded.Download, stringResource(Res.string.viewer_save), canExportImage, onSaveImage, cell)
                        ActionChip(Icons.Rounded.Share, stringResource(Res.string.share), hasText, onShare, cell)
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
                ActionChip(Icons.AutoMirrored.Rounded.Notes, stringResource(Res.string.viewer_all_text), hasText, onShowText, cell)
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

/** Original and translation, paragraph by paragraph in reading order: what "share" and "export text" produce. */
fun shareableText(result: ScanResult): String =
    result.inReadingOrder().joinToString("\n\n") { p ->
        listOfNotNull(p.readableText(), p.translation?.text).joinToString("\n")
    }

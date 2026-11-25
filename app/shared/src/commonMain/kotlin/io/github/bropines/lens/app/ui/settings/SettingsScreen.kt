package io.github.bropines.lens.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.Route
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.ProxyMode
import io.github.bropines.lens.app.data.ProxyPrefs
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.data.ThemeColor
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.ui.components.AppTopBar
import io.github.bropines.lens.app.ui.components.LanguagePickerSheet
import io.github.bropines.lens.app.ui.components.languageName
import io.github.bropines.lens.app.ui.theme.seed
import io.github.bropines.lens.app.update.UpdateStatus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The pages the settings are sorted into; each opens from its tile on the Settings tab. */
enum class SettingsPage { Translation, Overlay, Screen, Appearance, Network, Updates, App }

/**
 * The Settings tab: not a list. On top a card that says what the app is set to
 * do right now and lets the things changed most often be changed in place;
 * under it the rest, sorted into pages, as a grid of tiles that each say what
 * is set inside.
 */
@Composable
fun SettingsScreen(graph: AppGraph, settings: AppSettings, onOpen: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    fun open(page: SettingsPage) = onOpen(Route.Settings(page))

    val screen = graph.services.screenTranslate
    val updateStatus by graph.updates.status.collectAsState()
    val translators = settings.translators.enabled.joinToString(" → ") { graph.translator.nameOf(it) }
        .ifEmpty { stringResource(Res.string.translators_none) }

    Scaffold(
        // A tab: the dock below is the main screen's, and it has already taken the insets.
        contentWindowInsets = WindowInsets(0),
        topBar = { AppTopBar(stringResource(Res.string.tab_settings), stringResource(Res.string.about_version, graph.services.appVersion)) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupCard(
                settings = settings,
                hasOnDevice = graph.services.onDevice != null,
                update = ::update,
                onAppearance = { open(SettingsPage.Appearance) },
                onNetwork = { open(SettingsPage.Network) },
            )

            val tiles = buildList {
                add(Tile(Icons.Rounded.Translate, MaterialShapes.Cookie9Sided.toShape(), stringResource(Res.string.settings_section_translation), translators) { open(SettingsPage.Translation) })
                add(
                    Tile(
                        Icons.Rounded.Layers, MaterialShapes.Clover4Leaf.toShape(), stringResource(Res.string.settings_section_overlay_style),
                        layoutLabel(settings.readingLayout) + " · ${(settings.overlay.textScale * 100).roundToInt()}%",
                    ) { open(SettingsPage.Overlay) },
                )
                if (screen != null) {
                    val opaque by screen.opaque.collectAsState()
                    add(
                        Tile(
                            Icons.Rounded.ScreenshotMonitor, MaterialShapes.Cookie4Sided.toShape(), stringResource(Res.string.screen_translation),
                            stringResource(if (opaque) Res.string.screen_summary_opaque else Res.string.screen_summary_through),
                        ) { open(SettingsPage.Screen) },
                    )
                }
                add(
                    Tile(
                        Icons.Rounded.Palette, MaterialShapes.Flower.toShape(), stringResource(Res.string.settings_section_appearance),
                        themeLabel(settings.theme) + " · " + stringResource(shownThemeColor(settings.themeColor).label()),
                    ) { open(SettingsPage.Appearance) },
                )
                add(Tile(Icons.Rounded.Public, MaterialShapes.Pentagon.toShape(), stringResource(Res.string.network_title), proxySummary(settings.proxy)) { open(SettingsPage.Network) })
                add(
                    Tile(
                        Icons.Rounded.SystemUpdate, MaterialShapes.Sunny.toShape(), stringResource(Res.string.settings_section_updates),
                        when (val status = updateStatus) {
                            UpdateStatus.Checking -> stringResource(Res.string.update_checking)
                            is UpdateStatus.Available -> stringResource(Res.string.update_available, status.release.version)
                            is UpdateStatus.Failed -> status.message.asString()
                            UpdateStatus.Unknown, UpdateStatus.UpToDate -> stringResource(Res.string.update_installed, graph.updates.installedVersion)
                        },
                        // An update waiting is the one thing on this page that asks to be noticed.
                        highlighted = updateStatus is UpdateStatus.Available,
                    ) { open(SettingsPage.Updates) },
                )
                add(Tile(Icons.Rounded.Tune, MaterialShapes.Gem.toShape(), stringResource(Res.string.settings_section_app), stringResource(Res.string.settings_app_summary)) { open(SettingsPage.App) })
                add(
                    Tile(Icons.Rounded.Science, MaterialShapes.SoftBurst.toShape(), stringResource(Res.string.settings_section_experimental), stringResource(Res.string.account_title)) {
                        onOpen(Route.Account)
                    },
                )
            }
            // Two to a row, and each row as tall as its taller tile: a summary that wraps
            // (a long language, a large font) grows both instead of leaving a ragged edge.
            tiles.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { column, tile ->
                        CategoryTile(tile, accent = (row * 2 + column) % 3, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

private class Tile(
    val icon: ImageVector,
    val badge: Shape,
    val title: String,
    val summary: String,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * One page of settings as a tile: its name and what is set there now. The
 * badge behind the icon is one of Material's expressive shapes, a different
 * one per page and in one of three accent colours, so the grid is told apart
 * by eye before a word is read.
 */
@Composable
private fun CategoryTile(tile: Tile, accent: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (badge, onBadge) = when (accent) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    // Surface(onClick): the ripple is clipped to the rounded tile.
    Surface(
        onClick = tile.onClick,
        shape = MaterialTheme.shapes.large,
        color = if (tile.highlighted) scheme.tertiaryContainer else scheme.surfaceContainer,
        contentColor = if (tile.highlighted) scheme.onTertiaryContainer else scheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).background(if (tile.highlighted) scheme.tertiary else badge, tile.badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tile.icon, contentDescription = null, tint = if (tile.highlighted) scheme.onTertiary else onBadge, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    tile.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tile.highlighted) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The card at the top: what Seenly is set to do now. The two languages open
 * their pickers; the chips under them switch the things one changes between
 * one picture and the next, without a trip into a page.
 */
@Composable
private fun SetupCard(
    settings: AppSettings,
    hasOnDevice: Boolean,
    update: (transform: (AppSettings) -> AppSettings) -> Unit,
    onAppearance: () -> Unit,
    onNetwork: () -> Unit,
) {
    var picking by remember { mutableStateOf<Pick?>(null) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.settings_setup_title), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer.copy(alpha = 0.75f))
            // A flow, not a row: two long language names at a large font go on two lines instead of being cut.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                LanguagePill(languageName(settings.sourceLanguage)) { picking = Pick.Source }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                LanguagePill(languageName(settings.targetLanguage)) { picking = Pick.Target }
            }
            // No gap between the rows: each chip already stands in a touch target taller than itself.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupChip(layoutIcon(settings.readingLayout), layoutLabel(settings.readingLayout)) { picking = Pick.Layout }
                SetupChip(themeIcon(settings.theme), themeLabel(settings.theme)) {
                    // One tap, the next of three: quicker than a menu for something tried and switched back.
                    update { it.copy(theme = ThemeMode.entries[(it.theme.ordinal + 1) % ThemeMode.entries.size]) }
                }
                SetupChip(Icons.Rounded.Contrast, stringResource(Res.string.theme_amoled), active = settings.amoled) {
                    update { it.copy(amoled = !it.amoled) }
                }
                if (hasOnDevice) {
                    SetupChip(Icons.Rounded.CloudOff, stringResource(Res.string.settings_setup_offline), active = settings.onDevice.always) {
                        update { it.copy(onDevice = it.onDevice.copy(always = !it.onDevice.always)) }
                    }
                }
                SetupChip(
                    Icons.Rounded.VpnLock,
                    when (settings.proxy.mode) {
                        ProxyMode.None -> stringResource(Res.string.network_setup_none)
                        ProxyMode.System -> stringResource(Res.string.network_summary_system)
                        ProxyMode.Http -> "HTTP"
                        ProxyMode.Socks5 -> "SOCKS5"
                    },
                    active = settings.proxy.mode != ProxyMode.None,
                    onClick = onNetwork,
                )
                val palette = shownThemeColor(settings.themeColor)
                SetupChip(null, stringResource(palette.label()), swatch = swatchColor(palette), onClick = onAppearance)
            }
        }
    }

    when (picking) {
        Pick.Target -> LanguagePickerSheet(settings.targetLanguage, allowAuto = false, onDismiss = { picking = null }) { code ->
            if (code != null) update { it.copy(targetLanguage = code) }
            picking = null
        }
        Pick.Source -> LanguagePickerSheet(settings.sourceLanguage, allowAuto = true, onDismiss = { picking = null }) { code ->
            update { it.copy(sourceLanguage = code) }
            picking = null
        }
        Pick.Layout -> LayoutSheet(settings.readingLayout, onDismiss = { picking = null }) { layout ->
            update { it.copy(readingLayout = layout) }
            picking = null
        }
        null -> Unit
    }
}

private enum class Pick { Source, Target, Layout }

@Composable
private fun LanguagePill(name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A chip on the setup card. An [active] one is filled in the card's own text
 * colour, which stands out on every palette, AMOLED included, where a
 * FilterChip's default colours would sink into the container.
 */
@Composable
private fun SetupChip(icon: ImageVector?, label: String, active: Boolean = false, swatch: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) scheme.onPrimaryContainer else scheme.onPrimaryContainer.copy(alpha = 0.1f))
    val content by animateColorAsState(if (active) scheme.primaryContainer else scheme.onPrimaryContainer)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        modifier = Modifier.semantics { selected = active },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            if (swatch != null) Box(Modifier.size(18.dp).background(swatch, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The three layouts with what each is for: a quick pick, so a sheet. */
@Composable
private fun LayoutSheet(selected: ReadingLayout, onDismiss: () -> Unit, onPick: (ReadingLayout) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.settings_section_overlay),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ReadingLayout.entries.forEach { layout ->
                Surface(onClick = { onPick(layout) }, color = Color.Transparent) {
                    ListItem(
                        headlineContent = { Text(layoutLabel(layout)) },
                        supportingContent = { Text(layoutDescription(layout)) },
                        leadingContent = { SettingIcon(layoutIcon(layout)) },
                        trailingContent = { if (layout == selected) Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.language_selected)) },
                        colors = rowColors(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun layoutLabel(layout: ReadingLayout): String = stringResource(
    when (layout) {
        ReadingLayout.Auto -> Res.string.layout_auto
        ReadingLayout.InPlace -> Res.string.layout_in_place
        ReadingLayout.Reflow -> Res.string.layout_reflow
    },
)

@Composable
internal fun layoutDescription(layout: ReadingLayout): String = stringResource(
    when (layout) {
        ReadingLayout.Auto -> Res.string.layout_auto_desc
        ReadingLayout.InPlace -> Res.string.layout_in_place_desc
        ReadingLayout.Reflow -> Res.string.layout_reflow_desc
    },
)

internal fun layoutIcon(layout: ReadingLayout): ImageVector = when (layout) {
    ReadingLayout.Auto -> Icons.Rounded.AutoAwesome
    ReadingLayout.InPlace -> Icons.Rounded.ViewHeadline
    ReadingLayout.Reflow -> Icons.Rounded.ChatBubbleOutline
}

@Composable
internal fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> Res.string.theme_system
        ThemeMode.Light -> Res.string.theme_light
        ThemeMode.Dark -> Res.string.theme_dark
    },
)

internal fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.System -> Icons.Rounded.BrightnessAuto
    ThemeMode.Light -> Icons.Rounded.LightMode
    ThemeMode.Dark -> Icons.Rounded.DarkMode
}

/** The colour a palette is known by: its seed, or the scheme's own primary for the wallpaper's. */
@Composable
private fun swatchColor(color: ThemeColor): Color =
    if (color == ThemeColor.Wallpaper) MaterialTheme.colorScheme.primary else color.seed()

/** Where requests go, in a few words: for the Network tile and the page's top bar. */
@Composable
internal fun proxySummary(prefs: ProxyPrefs): String = when (prefs.mode) {
    ProxyMode.None -> stringResource(Res.string.network_summary_direct)
    ProxyMode.System -> stringResource(Res.string.network_summary_system)
    ProxyMode.Http -> "HTTP · ${prefs.host}:${prefs.port}"
    ProxyMode.Socks5 -> "SOCKS5 · ${prefs.host}:${prefs.port}"
}

package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatLineSpacing
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.GTranslate
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextRotateVertical
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.Route
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.EraseChoice
import io.github.bropines.lens.app.data.FontChoice
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.data.OutlineChoice
import io.github.bropines.lens.app.data.OverlayPrefs
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.data.RefineChoice
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.data.UpdatePrefs
import io.github.bropines.lens.app.data.VerticalChoice
import io.github.bropines.lens.app.di.AppGraph
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.screen.ScreenTranslate
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.ui.components.LanguagePickerSheet
import io.github.bropines.lens.app.ui.components.languageName
import io.github.bropines.lens.app.update.UpdateController
import io.github.bropines.lens.app.update.UpdateStatus
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** One of the settings pages behind the tiles of the Settings tab. */
@Composable
fun SettingsPageScreen(page: SettingsPage, graph: AppGraph, settings: AppSettings, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val update: (transform: (AppSettings) -> AppSettings) -> Unit = { transform -> scope.launch { graph.settings.update(transform) } }
    when (page) {
        SettingsPage.Translation -> TranslationPage(graph, settings, update, onOpen, onBack)
        SettingsPage.Overlay -> OverlayPage(settings, update, onBack)
        // Where the platform has no screen translator there is no tile to come here from.
        SettingsPage.Screen -> graph.services.screenTranslate?.let { ScreenPage(it, onBack) }
        SettingsPage.Appearance -> AppearancePage(settings, update, onBack)
        SettingsPage.Network -> NetworkScreen(graph.network, settings, onBack)
        SettingsPage.Updates -> UpdatesPage(graph.updates, settings, update, onBack)
        SettingsPage.App -> AppPage(graph, onOpen, onBack)
    }
}

private typealias Update = (transform: (AppSettings) -> AppSettings) -> Unit

@Composable
private fun TranslationPage(graph: AppGraph, settings: AppSettings, update: Update, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    SettingsScaffold(
        title = stringResource(Res.string.settings_section_translation),
        subtitle = languageName(settings.sourceLanguage) + " → " + languageName(settings.targetLanguage),
        onBack = onBack,
    ) {
        SettingsCard(stringResource(Res.string.settings_languages)) {
            Setting(Icons.Rounded.Translate, stringResource(Res.string.settings_target), languageName(settings.targetLanguage)) { picker = "target" }
            Setting(Icons.Rounded.Language, stringResource(Res.string.source_language), languageName(settings.sourceLanguage)) { picker = "source" }
            Setting(
                Icons.Rounded.DocumentScanner,
                stringResource(Res.string.settings_ocr_hint),
                if (settings.ocrLanguage.isEmpty()) stringResource(Res.string.settings_ocr_hint_none) else languageName(settings.ocrLanguage),
            ) { picker = "ocr" }
        }
        SettingsCard(stringResource(Res.string.text_translation)) {
            Setting(
                Icons.Rounded.GTranslate,
                stringResource(Res.string.translators),
                settings.translators.enabled.joinToString(" → ") { graph.translator.nameOf(it) }.ifEmpty { stringResource(Res.string.translators_none) },
            ) { onOpen(Route.Translators) }
        }
        if (graph.services.onDevice != null) {
            SettingsCard {
                Setting(
                    Icons.Rounded.CloudOff,
                    stringResource(Res.string.on_device_title),
                    stringResource(
                        when {
                            settings.onDevice.always -> Res.string.on_device_summary_always
                            settings.onDevice.fallback -> Res.string.on_device_summary_fallback
                            else -> Res.string.on_device_summary_off
                        },
                    ),
                ) { onOpen(Route.OnDevice) }
            }
        }
    }

    when (picker) {
        "target" -> LanguagePickerSheet(settings.targetLanguage, allowAuto = false, onDismiss = { picker = null }) { code ->
            if (code != null) update { it.copy(targetLanguage = code) }
            picker = null
        }
        "source" -> LanguagePickerSheet(settings.sourceLanguage, allowAuto = true, onDismiss = { picker = null }) { code ->
            update { it.copy(sourceLanguage = code) }
            picker = null
        }
        "ocr" -> LanguagePickerSheet(settings.ocrLanguage.ifEmpty { null }, allowAuto = true, onDismiss = { picker = null }) { code ->
            update { it.copy(ocrLanguage = code.orEmpty()) }
            picker = null
        }
    }
}

/** How the translation is drawn over a picture, with a sample that follows every change. */
@Composable
private fun OverlayPage(settings: AppSettings, update: Update, onBack: () -> Unit) {
    val o = settings.overlay
    fun overlay(transform: (OverlayPrefs) -> OverlayPrefs) = update { it.copy(overlay = transform(it.overlay)) }
    val auto = stringResource(Res.string.auto)
    SettingsScaffold(
        title = stringResource(Res.string.settings_section_overlay_style),
        subtitle = layoutLabel(settings.readingLayout) + " · ${(o.textScale * 100).roundToInt()}%",
        onBack = onBack,
    ) {
        OverlayPreview(settings)

        SettingsCard(stringResource(Res.string.settings_section_overlay)) {
            ChipChoice(
                layoutIcon(settings.readingLayout), stringResource(Res.string.settings_layout),
                ReadingLayout.entries.map { it to layoutLabel(it) },
                settings.readingLayout,
                description = layoutDescription(settings.readingLayout),
            ) { v -> update { it.copy(readingLayout = v) } }
        }

        SettingsCard(stringResource(Res.string.settings_section_style)) {
            ChipChoice(
                Icons.Rounded.AutoFixHigh, stringResource(Res.string.style_erase),
                listOf(EraseChoice.Auto to auto, EraseChoice.Patch to stringResource(Res.string.erase_patch), EraseChoice.Fill to stringResource(Res.string.erase_fill)),
                o.erase,
            ) { v -> overlay { it.copy(erase = v) } }
            ChipChoice(
                Icons.Rounded.BorderStyle, stringResource(Res.string.style_outline),
                listOf(
                    OutlineChoice.Auto to auto,
                    OutlineChoice.None to stringResource(Res.string.outline_none),
                    OutlineChoice.Thin to stringResource(Res.string.outline_thin),
                    OutlineChoice.Bold to stringResource(Res.string.outline_bold),
                ),
                o.outline,
            ) { v -> overlay { it.copy(outline = v) } }
            ChipChoice(
                Icons.Rounded.FontDownload, stringResource(Res.string.style_font),
                listOf(
                    FontChoice.System to stringResource(Res.string.font_system),
                    FontChoice.Sans to "Sans",
                    FontChoice.Serif to "Serif",
                    FontChoice.Mono to stringResource(Res.string.font_mono),
                ),
                o.font,
            ) { v -> overlay { it.copy(font = v) } }
            ChipChoice(
                Icons.Rounded.TextRotateVertical, stringResource(Res.string.style_vertical),
                listOf(
                    VerticalChoice.Auto to auto,
                    VerticalChoice.Keep to stringResource(Res.string.vertical_keep),
                    VerticalChoice.Horizontal to stringResource(Res.string.vertical_horizontal),
                ),
                o.vertical,
            ) { v -> overlay { it.copy(vertical = v) } }
            SliderSetting(
                Icons.Rounded.FormatSize, stringResource(Res.string.style_text_size), "${(o.textScale * 100).roundToInt()}%",
                value = o.textScale, range = 0.7f..1.3f, steps = 11,
            ) { v -> overlay { it.copy(textScale = (v * 20).roundToInt() / 20f) } }
            SliderSetting(
                Icons.Rounded.TextFields, stringResource(Res.string.style_min_size), if (o.minFontSp == 0) auto else "${o.minFontSp} sp",
                value = o.minFontSp.toFloat(), range = 0f..16f, steps = 15,
            ) { v -> overlay { it.copy(minFontSp = v.roundToInt()) } }
        }

        // Line-by-line refinements; each is optional because none is right for every page.
        // "Auto" decides per paragraph from its shape; On and Off force it.
        SettingsCard(stringResource(Res.string.settings_section_refine)) {
            val refine = listOf(
                RefineChoice.Auto to auto,
                RefineChoice.On to stringResource(Res.string.choice_on),
                RefineChoice.Off to stringResource(Res.string.choice_off),
            )
            ChipChoice(Icons.Rounded.FormatLineSpacing, stringResource(Res.string.style_uniform_size), refine, o.uniformLineSize) { v ->
                overlay { it.copy(uniformLineSize = v) }
            }
            ChipChoice(Icons.AutoMirrored.Rounded.FormatAlignLeft, stringResource(Res.string.style_grow_start), refine, o.growFromLineStart) { v ->
                overlay { it.copy(growFromLineStart = v) }
            }
            ChipChoice(Icons.Rounded.WrapText, stringResource(Res.string.style_reflow_dense), refine, o.reflowDense) { v ->
                overlay { it.copy(reflowDense = v) }
            }
        }
        Footnote(stringResource(Res.string.style_refine_hint))
    }
}

/** The floating screen translator: the bubble itself, and whether its translation may cover the original. */
@Composable
private fun ScreenPage(screen: ScreenTranslate, onBack: () -> Unit) {
    val opaque by screen.opaque.collectAsState()
    val running by screen.running.collectAsState()
    SettingsScaffold(
        title = stringResource(Res.string.screen_translation),
        subtitle = stringResource(if (opaque) Res.string.screen_summary_opaque else Res.string.screen_summary_through),
        onBack = onBack,
    ) {
        SettingsCard {
            Toggle(
                Icons.Rounded.Translate,
                stringResource(Res.string.screen_bubble),
                stringResource(if (running) Res.string.home_screen_running else Res.string.home_screen_desc),
                running,
            ) { on -> if (on) screen.start() else screen.stop() }
            Setting(
                Icons.Rounded.Layers,
                stringResource(Res.string.settings_opaque),
                stringResource(if (opaque) Res.string.settings_opaque_on else Res.string.settings_opaque_off),
            ) { screen.openOpaqueSettings() }
        }
    }
}

@Composable
private fun AppearancePage(settings: AppSettings, update: Update, onBack: () -> Unit) {
    SettingsScaffold(
        title = stringResource(Res.string.settings_section_appearance),
        subtitle = themeLabel(settings.theme) + " · " + stringResource(shownThemeColor(settings.themeColor).label()),
        onBack = onBack,
    ) {
        SettingsCard {
            ChipChoice(
                themeIcon(settings.theme), stringResource(Res.string.settings_theme),
                ThemeMode.entries.map { it to themeLabel(it) },
                settings.theme,
            ) { v -> update { it.copy(theme = v) } }
            ThemeColorPicker(settings.themeColor) { c -> update { it.copy(themeColor = c) } }
            Toggle(
                Icons.Rounded.Contrast, stringResource(Res.string.theme_amoled),
                stringResource(Res.string.theme_amoled_desc), settings.amoled,
            ) { on -> update { it.copy(amoled = on) } }
        }
        SettingsCard {
            // The app's own language. English and Russian are named natively, so each reads right whatever is chosen.
            ChipChoice(
                Icons.Rounded.Language, stringResource(Res.string.settings_ui_language),
                listOf(
                    null to stringResource(Res.string.ui_language_system),
                    "en" to Languages.nativeName("en"),
                    "ru" to Languages.nativeName("ru"),
                ),
                settings.uiLanguage,
            ) { v -> update { it.copy(uiLanguage = v) } }
        }
    }
}

/** The updater: whether it asks on its own, which channel, and the check itself. */
@Composable
private fun UpdatesPage(updates: UpdateController, settings: AppSettings, update: Update, onBack: () -> Unit) {
    val status by updates.status.collectAsState()
    fun prefs(transform: (UpdatePrefs) -> UpdatePrefs) = update { it.copy(updates = transform(it.updates)) }
    SettingsScaffold(
        title = stringResource(Res.string.settings_section_updates),
        subtitle = stringResource(Res.string.update_installed, updates.installedVersion),
        onBack = onBack,
    ) {
        SettingsCard {
            Setting(
                Icons.Rounded.SystemUpdate,
                stringResource(Res.string.update_check),
                when (val s = status) {
                    UpdateStatus.Unknown -> stringResource(Res.string.update_installed, updates.installedVersion)
                    UpdateStatus.Checking -> stringResource(Res.string.update_checking)
                    UpdateStatus.UpToDate -> stringResource(Res.string.update_up_to_date, updates.installedVersion)
                    is UpdateStatus.Available -> stringResource(Res.string.update_available, s.release.version)
                    is UpdateStatus.Failed -> s.message.asString()
                },
            ) {
                // An update already found is shown again; otherwise ask.
                if (status is UpdateStatus.Available) updates.showDetails() else updates.check()
            }
            Setting(Icons.Rounded.NewReleases, stringResource(Res.string.whats_new), stringResource(Res.string.whats_new_desc), updates::showWhatsNew)
        }
        SettingsCard {
            Toggle(
                Icons.Rounded.Update, stringResource(Res.string.update_auto), stringResource(Res.string.update_auto_desc),
                settings.updates.autoCheck,
            ) { on -> prefs { it.copy(autoCheck = on) } }
            Toggle(
                Icons.Rounded.Science, stringResource(Res.string.update_beta), stringResource(Res.string.update_beta_desc),
                settings.updates.beta,
            ) { on -> prefs { it.copy(beta = on) } }
        }
    }
}

/** The settings as a file, out and in, and the page about the app. */
@Composable
private fun AppPage(graph: AppGraph, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repository = graph.settings
    val account = graph.account
    val network = graph.network
    val accountState by account.state.collectAsState()
    // Whether the export about to be saved carries a secret: asked each time, never the default.
    var exportCookies by remember { mutableStateOf(false) }
    var exportProxyPassword by remember { mutableStateOf(false) }
    var askExport by remember { mutableStateOf(false) }
    val exporter = rememberFileSaverLauncher(dialogSettings = FileKitDialogSettings.createDefault()) { file: PlatformFile? ->
        if (file == null) return@rememberFileSaverLauncher
        scope.launch {
            val cookies = if (exportCookies) account.exportCookies() else null
            val password = if (exportProxyPassword) network.passwordFor(repository.settings.first().proxy).ifEmpty { null } else null
            val message = runCatching { file.writeString(repository.exportJson(cookies, password)) }
                .fold({ getString(Res.string.settings_saved) }, { getString(Res.string.settings_save_failed, it.message.orEmpty()) })
            snackbar.showSnackbar(message)
        }
    }
    // Any file: some file managers do not tag .json as JSON.
    val importer = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val message = runCatching { repository.importJson(file.readString()) }
                .fold(
                    { secrets ->
                        // A proxy password in the file belongs to the proxy in the file. Without one, a
                        // password saved here stays, and is used only if the server is still the same.
                        secrets.proxyPassword?.let { network.savePassword(repository.settings.first().proxy, it) }
                        // A backup made with the account in it brings the account along.
                        if (secrets.googleCookies != null && account.connect(secrets.googleCookies)) {
                            getString(Res.string.settings_loaded_with_account)
                        } else {
                            getString(Res.string.settings_loaded)
                        }
                    },
                    {
                        if (it is IllegalArgumentException) {
                            getString(Res.string.settings_not_a_backup)
                        } else {
                            getString(Res.string.settings_read_failed, it.message.orEmpty())
                        }
                    },
                )
            snackbar.showSnackbar(message)
        }
    }

    SettingsScaffold(
        title = stringResource(Res.string.settings_section_app),
        subtitle = stringResource(Res.string.settings_app_summary),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        SettingsCard(stringResource(Res.string.settings_backup)) {
            Setting(
                Icons.Rounded.Upload, stringResource(Res.string.settings_export),
                stringResource(Res.string.settings_export_desc),
            ) {
                exportCookies = false
                exportProxyPassword = false
                // With a secret on the device, ask first whether it goes into the file.
                if (accountState.connected || network.hasPassword()) {
                    askExport = true
                } else {
                    exporter.launch(suggestedName = "lens-settings", extension = "json")
                }
            }
            Setting(Icons.Rounded.Download, stringResource(Res.string.settings_import), stringResource(Res.string.settings_import_desc)) { importer.launch() }
        }
        SettingsCard {
            Setting(Icons.Rounded.Info, stringResource(Res.string.about_title), stringResource(Res.string.settings_about_desc)) { onOpen(Route.About) }
        }
    }

    if (askExport) {
        AlertDialog(
            onDismissRequest = { askExport = false },
            title = { Text(stringResource(Res.string.settings_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.settings_export_desc))
                    if (accountState.connected) {
                        SecretOption(
                            stringResource(Res.string.settings_export_account),
                            stringResource(Res.string.settings_export_account_warning),
                            exportCookies,
                        ) { exportCookies = it }
                    }
                    if (network.hasPassword()) {
                        SecretOption(
                            stringResource(Res.string.settings_export_proxy),
                            stringResource(Res.string.settings_export_proxy_warning),
                            exportProxyPassword,
                        ) { exportProxyPassword = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askExport = false
                    exporter.launch(suggestedName = "lens-settings", extension = "json")
                }) { Text(stringResource(Res.string.settings_export_save)) }
            },
            dismissButton = { TextButton(onClick = { askExport = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** A secret the export may carry: off unless ticked, and with what ticking it means said underneath. */
@Composable
private fun SecretOption(label: String, warning: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
    if (checked) {
        Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

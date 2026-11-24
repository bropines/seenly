package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextRotateVertical
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.bropines.lens.app.data.EraseChoice
import io.github.bropines.lens.app.data.FontChoice
import io.github.bropines.lens.app.data.OutlineChoice
import io.github.bropines.lens.app.data.OverlayPrefs
import io.github.bropines.lens.app.data.VerticalChoice
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GTranslate
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import androidx.compose.runtime.collectAsState
import io.github.bropines.lens.app.screen.ScreenTranslate
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.data.ReadingLayout
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.TextEngine
import io.github.bropines.lens.app.data.ThemeMode
import io.github.bropines.lens.app.ui.components.LanguagePickerSheet
import io.github.bropines.lens.app.ui.components.languageName
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    settings: AppSettings,
    onBack: (() -> Unit)?,
    onAbout: () -> Unit = {},
    onTranslators: () -> Unit = {},
    translatorsSummary: String = "",
    screen: ScreenTranslate? = null,
) {
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { repository.update(transform) }
    var picker by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val exporter = rememberFileSaverLauncher(dialogSettings = FileKitDialogSettings.createDefault()) { file: PlatformFile? ->
        if (file == null) return@rememberFileSaverLauncher
        scope.launch {
            val message = runCatching { file.writeString(repository.exportJson()) }
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
                    { getString(Res.string.settings_loaded) },
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = if (onBack == null) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.tab_settings)) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(stringResource(Res.string.settings_section_translation))
            Setting(Icons.Rounded.Translate, stringResource(Res.string.settings_target), languageName(settings.targetLanguage)) { picker = "target" }
            Setting(Icons.Rounded.Language, stringResource(Res.string.source_language), languageName(settings.sourceLanguage)) { picker = "source" }
            Setting(
                Icons.Rounded.DocumentScanner,
                stringResource(Res.string.settings_ocr_hint),
                if (settings.ocrLanguage.isEmpty()) stringResource(Res.string.settings_ocr_hint_none) else languageName(settings.ocrLanguage),
            ) { picker = "ocr" }

            Section(stringResource(Res.string.settings_section_overlay))
            Choice(Icons.Rounded.AutoAwesome, stringResource(Res.string.layout_auto), stringResource(Res.string.layout_auto_desc), settings.readingLayout == ReadingLayout.Auto) {
                update { it.copy(readingLayout = ReadingLayout.Auto) }
            }
            Choice(Icons.Rounded.ViewHeadline, stringResource(Res.string.layout_in_place), stringResource(Res.string.layout_in_place_desc), settings.readingLayout == ReadingLayout.InPlace) {
                update { it.copy(readingLayout = ReadingLayout.InPlace) }
            }
            Choice(Icons.Rounded.ChatBubbleOutline, stringResource(Res.string.layout_reflow), stringResource(Res.string.layout_reflow_desc), settings.readingLayout == ReadingLayout.Reflow) {
                update { it.copy(readingLayout = ReadingLayout.Reflow) }
            }

            Section(stringResource(Res.string.settings_section_style))
            val o = settings.overlay
            fun overlay(transform: (OverlayPrefs) -> OverlayPrefs) = update { it.copy(overlay = transform(it.overlay)) }
            val auto = stringResource(Res.string.auto)
            Segmented(
                Icons.Rounded.AutoFixHigh, stringResource(Res.string.style_erase),
                listOf(EraseChoice.Auto to auto, EraseChoice.Patch to stringResource(Res.string.erase_patch), EraseChoice.Fill to stringResource(Res.string.erase_fill)),
                o.erase,
            ) { v -> overlay { it.copy(erase = v) } }
            Segmented(
                Icons.Rounded.BorderStyle, stringResource(Res.string.style_outline),
                listOf(
                    OutlineChoice.Auto to auto,
                    OutlineChoice.None to stringResource(Res.string.outline_none),
                    OutlineChoice.Thin to stringResource(Res.string.outline_thin),
                    OutlineChoice.Bold to stringResource(Res.string.outline_bold),
                ),
                o.outline,
            ) { v -> overlay { it.copy(outline = v) } }
            Segmented(
                Icons.Rounded.FontDownload, stringResource(Res.string.style_font),
                listOf(
                    FontChoice.System to stringResource(Res.string.font_system),
                    FontChoice.Sans to "Sans",
                    FontChoice.Serif to "Serif",
                    FontChoice.Mono to stringResource(Res.string.font_mono),
                ),
                o.font,
            ) { v -> overlay { it.copy(font = v) } }
            Segmented(
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

            if (screen != null) {
                val opaque by screen.opaque.collectAsState()
                Section(stringResource(Res.string.screen_translation))
                Setting(
                    Icons.Rounded.Layers,
                    stringResource(Res.string.settings_opaque),
                    stringResource(if (opaque) Res.string.settings_opaque_on else Res.string.settings_opaque_off),
                ) { screen.openOpaqueSettings() }
            }

            Section(stringResource(Res.string.text_translation))
            Setting(Icons.Rounded.GTranslate, stringResource(Res.string.translators), translatorsSummary, onTranslators)

            Section(stringResource(Res.string.settings_section_appearance))
            // The app's own language. English and Russian are named natively, so each reads right whatever is chosen.
            Segmented(
                Icons.Rounded.Language, stringResource(Res.string.settings_ui_language),
                listOf(
                    null to stringResource(Res.string.ui_language_system),
                    "en" to Languages.nativeName("en"),
                    "ru" to Languages.nativeName("ru"),
                ),
                settings.uiLanguage,
            ) { v -> update { it.copy(uiLanguage = v) } }
            ThemeMode.entries.forEach { mode ->
                val label = when (mode) {
                    ThemeMode.System -> stringResource(Res.string.theme_system)
                    ThemeMode.Light -> stringResource(Res.string.theme_light)
                    ThemeMode.Dark -> stringResource(Res.string.theme_dark)
                }
                val icon = when (mode) {
                    ThemeMode.System -> Icons.Rounded.BrightnessAuto
                    ThemeMode.Light -> Icons.Rounded.LightMode
                    ThemeMode.Dark -> Icons.Rounded.DarkMode
                }
                Choice(icon, label, null, settings.theme == mode) { update { it.copy(theme = mode) } }
            }

            Section(stringResource(Res.string.settings_section_app))
            Setting(
                Icons.Rounded.Upload, stringResource(Res.string.settings_export),
                stringResource(Res.string.settings_export_desc),
            ) { exporter.launch(suggestedName = "lens-settings", extension = "json") }
            Setting(Icons.Rounded.Download, stringResource(Res.string.settings_import), stringResource(Res.string.settings_import_desc)) { importer.launch() }
            Setting(Icons.Rounded.Info, stringResource(Res.string.about_title), stringResource(Res.string.settings_about_desc), onAbout)
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

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Setting(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { SettingIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A setting with a few named values, picked in a connected segmented row under its title. */
@Composable
private fun <T> Segmented(icon: ImageVector, title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {},
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        @Suppress("DEPRECATION")
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { SettingIcon(icon) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

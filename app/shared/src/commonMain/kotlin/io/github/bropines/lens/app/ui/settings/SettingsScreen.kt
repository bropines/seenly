package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GTranslate
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: SettingsRepository, settings: AppSettings, onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { repository.update(transform) }
    var picker by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = if (onBack == null) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Перевод")
            Setting(Icons.Rounded.Translate, "Переводить на", Languages.nameOf(settings.targetLanguage)) { picker = "target" }
            Setting(Icons.Rounded.Language, "Язык оригинала", Languages.nameOf(settings.sourceLanguage)) { picker = "source" }
            Setting(
                Icons.Rounded.DocumentScanner,
                "Подсказка для распознавания",
                if (settings.ocrLanguage.isEmpty()) "Не указана — Lens определит сам" else Languages.nameOf(settings.ocrLanguage),
            ) { picker = "ocr" }

            Section("Наложение перевода")
            Choice(Icons.Rounded.AutoAwesome, "Автоматически", "Манга — по абзацам, остальное — строка в строку", settings.readingLayout == ReadingLayout.Auto) {
                update { it.copy(readingLayout = ReadingLayout.Auto) }
            }
            Choice(Icons.Rounded.ViewHeadline, "Строка в строку", "Как в Chrome: для вывесок и скриншотов", settings.readingLayout == ReadingLayout.InPlace) {
                update { it.copy(readingLayout = ReadingLayout.InPlace) }
            }
            Choice(Icons.Rounded.ChatBubbleOutline, "По абзацам", "Текст перетекает внутри пузыря: для манги и комиксов", settings.readingLayout == ReadingLayout.Reflow) {
                update { it.copy(readingLayout = ReadingLayout.Reflow) }
            }

            Section("Перевод текста")
            Choice(Icons.Rounded.GTranslate, "Google Переводчик", "Словарь, транслитерация, длинные тексты", settings.textEngine == TextEngine.Google) {
                update { it.copy(textEngine = TextEngine.Google) }
            }

            Section("Оформление")
            ThemeMode.entries.forEach { mode ->
                val label = when (mode) {
                    ThemeMode.System -> "Как в системе"
                    ThemeMode.Light -> "Светлая"
                    ThemeMode.Dark -> "Тёмная"
                }
                val icon = when (mode) {
                    ThemeMode.System -> Icons.Rounded.BrightnessAuto
                    ThemeMode.Light -> Icons.Rounded.LightMode
                    ThemeMode.Dark -> Icons.Rounded.DarkMode
                }
                Choice(icon, label, null, settings.theme == mode) { update { it.copy(theme = mode) } }
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

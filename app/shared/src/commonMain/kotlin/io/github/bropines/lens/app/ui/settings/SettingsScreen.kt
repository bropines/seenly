package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.clickable
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
fun SettingsScreen(repository: SettingsRepository, settings: AppSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { repository.update(transform) }
    var picker by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Перевод")
            Setting("Переводить на", Languages.nameOf(settings.targetLanguage)) { picker = "target" }
            Setting("Язык оригинала", Languages.nameOf(settings.sourceLanguage)) { picker = "source" }
            Setting(
                "Подсказка для распознавания",
                if (settings.ocrLanguage.isEmpty()) "Не указана — Lens определит сам" else Languages.nameOf(settings.ocrLanguage),
            ) { picker = "ocr" }

            Section("Наложение перевода")
            Choice("Строка в строку", "Как в Chrome: для вывесок и скриншотов", settings.readingLayout == ReadingLayout.InPlace) {
                update { it.copy(readingLayout = ReadingLayout.InPlace) }
            }
            Choice("По абзацам", "Текст перетекает внутри пузыря: для манги и комиксов", settings.readingLayout == ReadingLayout.Reflow) {
                update { it.copy(readingLayout = ReadingLayout.Reflow) }
            }

            Section("Перевод текста")
            Choice("Google Переводчик", "Словарь, транслитерация, длинные тексты", settings.textEngine == TextEngine.Google) {
                update { it.copy(textEngine = TextEngine.Google) }
            }

            Section("Оформление")
            ThemeMode.entries.forEach { mode ->
                val label = when (mode) {
                    ThemeMode.System -> "Как в системе"
                    ThemeMode.Light -> "Светлая"
                    ThemeMode.Dark -> "Тёмная"
                }
                Choice(label, null, settings.theme == mode) { update { it.copy(theme = mode) } }
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
private fun Setting(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

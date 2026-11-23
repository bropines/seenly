package io.github.bropines.lens.app.ui.text

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.usefulSourceRomanisation
import io.github.bropines.lens.app.data.usefulTargetRomanisation
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.ui.components.LanguageBar

/** Google Translate for typed or pasted text, with transliteration and a dictionary for single words. */
@Composable
fun TextTranslateScreen(vm: TextTranslateViewModel, services: PlatformServices, onBack: (() -> Unit)?) {
    val state by vm.state.collectAsState()
    Scaffold(
        contentWindowInsets = if (onBack == null) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("Перевод текста") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageBar(source = state.source, target = state.target, onSource = vm::setSource, onTarget = vm::setTarget)
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::setInput,
                placeholder = { Text("Введите или вставьте текст") },
                trailingIcon = if (state.input.isNotEmpty()) {
                    { IconButton(onClick = { vm.setInput("") }) { Icon(Icons.Rounded.Clear, "Очистить") } }
                } else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                shape = MaterialTheme.shapes.large,
            )
            if (state.loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val result = state.result
            if (result != null && state.input.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(result.text, style = MaterialTheme.typography.headlineSmall) }
                        result.usefulTargetRomanisation(state.target)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { services.copyText(result.text) }) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
                            IconButton(onClick = { services.shareText(result.text) }) { Icon(Icons.Rounded.Share, "Поделиться") }
                        }
                    }
                }
                // Romanisation of the source (e.g. Japanese romaji); English phonetics only confuse.
                result.usefulSourceRomanisation()?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result.dictionary.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.partOfSpeech, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        entry.backTranslations.ifEmpty { entry.terms.map { it to emptyList() } }.forEach { (term, back) ->
                            Text(
                                if (back.isEmpty()) term else "$term — ${back.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

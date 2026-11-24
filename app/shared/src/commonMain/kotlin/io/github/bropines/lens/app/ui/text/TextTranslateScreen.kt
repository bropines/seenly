package io.github.bropines.lens.app.ui.text

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
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
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** Google Translate for typed or pasted text, with transliteration and a dictionary for single words. */
@Composable
fun TextTranslateScreen(vm: TextTranslateViewModel, services: PlatformServices, onBack: (() -> Unit)?) {
    val state by vm.state.collectAsState()
    Scaffold(
        contentWindowInsets = if (onBack == null) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.text_translation)) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
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
                placeholder = { Text(stringResource(Res.string.text_input_hint)) },
                trailingIcon = if (state.input.isNotEmpty()) {
                    { IconButton(onClick = { vm.setInput("") }) { Icon(Icons.Rounded.Clear, stringResource(Res.string.clear)) } }
                } else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                shape = MaterialTheme.shapes.large,
            )
            // Which engine: the user's order (with fail-over) or one picked for this text.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.engine == null, onClick = { vm.setEngine(null) }, label = { Text(stringResource(Res.string.auto)) })
                vm.translator.enabled().forEach { engine ->
                    FilterChip(selected = state.engine == engine.id, onClick = { vm.setEngine(engine.id) }, label = { Text(engine.name) })
                }
            }
            if (state.loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }

            val pipeline = state.result
            if (pipeline != null && state.input.isNotBlank()) {
                val result = pipeline.translation
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(result.text, style = MaterialTheme.typography.headlineSmall) }
                        result.usefulTargetRomanisation(state.target)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                vm.translator.nameOf(pipeline.engineId).let { engine ->
                                    if (pipeline.failures.any { !it.skipped }) stringResource(Res.string.text_others_failed, engine) else engine
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { services.copyText(result.text) }) { Icon(Icons.Rounded.ContentCopy, stringResource(Res.string.copy)) }
                            IconButton(onClick = { services.shareText(result.text) }) { Icon(Icons.Rounded.Share, stringResource(Res.string.share)) }
                        }
                    }
                }
                // Romanisation of the source (e.g. Japanese romaji); English phonetics only confuse.
                result.usefulSourceRomanisation()?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result.alternatives.takeIf { it.isNotEmpty() }?.let { alts ->
                    Text(stringResource(Res.string.text_more_variants, alts.take(4).joinToString(" · ")), style = MaterialTheme.typography.bodyMedium)
                }
                result.dictionary.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOfNotNull(entry.partOfSpeech, entry.transcription?.let { "[$it]" }).joinToString(" "),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        entry.terms.forEach { term ->
                            Text(
                                if (term.meanings.isEmpty()) term.text else "${term.text} — ${term.meanings.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

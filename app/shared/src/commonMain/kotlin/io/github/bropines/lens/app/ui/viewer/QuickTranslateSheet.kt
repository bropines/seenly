package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.usefulSourceRomanisation
import io.github.bropines.lens.app.data.usefulTargetRomanisation
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.gtranslate.GoogleTranslation
import io.github.bropines.lens.gtranslate.GoogleTranslator
import kotlinx.coroutines.CancellationException

private sealed interface Quick {
    data object Loading : Quick
    data class Done(val translation: GoogleTranslation) : Quick
    data class Failed(val message: String) : Quick
}

/** Google Translate for a selection or paragraph, without leaving the image. */
@Composable
fun QuickTranslateSheet(
    text: String,
    target: String,
    translator: GoogleTranslator,
    services: PlatformServices,
    onDismiss: () -> Unit,
    onOpenFull: () -> Unit,
) {
    val state by produceState<Quick>(Quick.Loading, text, target) {
        value = try {
            Quick.Done(translator.translate(text, target, uiLanguage = "ru", dictionary = text.length <= 40))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Quick.Failed(e.message ?: "Ошибка перевода")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
            (state as? Quick.Done)?.translation?.usefulSourceRomanisation()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (val s = state) {
                Quick.Loading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                is Quick.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is Quick.Done -> {
                    SelectionContainer { Text(s.translation.text, style = MaterialTheme.typography.titleLarge) }
                    s.translation.usefulTargetRomanisation(target)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    s.translation.dictionary.take(3).forEach { entry ->
                        Text(
                            "${entry.partOfSpeech}: ${entry.terms.take(6).joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                val translated = (state as? Quick.Done)?.translation?.text
                FilledTonalButton(onClick = { translated?.let(services::copyText) }, enabled = translated != null) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 6.dp))
                    Text("Копировать")
                }
                OutlinedButton(onClick = onOpenFull) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.padding(end = 6.dp))
                    Text("В переводчике")
                }
            }
        }
    }
}

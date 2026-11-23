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
import androidx.compose.material.icons.rounded.GTranslate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.Languages
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.protocol.Paragraph

/** A tapped paragraph: its original next to its translation, both copyable. */
@Composable
fun ParagraphSheet(
    paragraph: Paragraph,
    services: PlatformServices,
    onDismiss: () -> Unit,
    onTranslateWithGoogle: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val translation = paragraph.translation
            Text(
                "Оригинал" + (paragraph.language ?: translation?.sourceLanguage)?.let { " · ${Languages.nameOf(it)}" }.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer { Text(paragraph.text, style = MaterialTheme.typography.bodyLarge) }
            if (translation != null) {
                HorizontalDivider()
                Text(
                    "Перевод · ${Languages.nameOf(translation.targetLanguage)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer { Text(translation.text, style = MaterialTheme.typography.titleMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilledTonalButton(onClick = { services.copyText(paragraph.text) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 6.dp))
                    Text("Оригинал")
                }
                if (translation != null) {
                    FilledTonalButton(onClick = { services.copyText(translation.text) }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 6.dp))
                        Text("Перевод")
                    }
                }
                OutlinedButton(onClick = { onTranslateWithGoogle(paragraph.text) }) {
                    Icon(Icons.Rounded.GTranslate, contentDescription = "Открыть в переводчике")
                }
            }
        }
    }
}

package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GTranslate
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.protocol.ScanResult

private enum class TextView { Both, Original, Translation }

/** The recognised text, paragraph by paragraph, selectable and copyable. */
@Composable
fun TextSheet(
    result: ScanResult,
    services: PlatformServices,
    onDismiss: () -> Unit,
    onTranslateWithGoogle: (String) -> Unit,
) {
    var view by rememberSaveable { mutableStateOf(TextView.Both) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            val options = listOf(TextView.Both to "Оба", TextView.Original to "Оригинал", TextView.Translation to "Перевод")
            options.forEachIndexed { index, (option, label) ->
                ToggleButton(
                    checked = view == option,
                    onCheckedChange = { view = option },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = {
                services.copyText(
                    when (view) {
                        TextView.Original -> result.text
                        TextView.Translation -> result.translation.orEmpty()
                        TextView.Both -> shareableText(result)
                    },
                )
            }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Копировать всё") }
            IconButton(onClick = { services.shareText(shareableText(result)) }) {
                Icon(Icons.Rounded.Share, contentDescription = "Поделиться")
            }
        }
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(result.paragraphs) { _, paragraph ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (view != TextView.Translation) {
                                    Text(
                                        paragraph.text,
                                        style = if (view == TextView.Original) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                        color = if (view == TextView.Original) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val translation = paragraph.translation?.text
                                if (view != TextView.Original && translation != null) {
                                    Text(translation, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Column {
                            IconButton(onClick = {
                                services.copyText(if (view == TextView.Translation) paragraph.translation?.text ?: paragraph.text else paragraph.text)
                            }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Копировать абзац") }
                            IconButton(onClick = { onTranslateWithGoogle(paragraph.text) }) {
                                Icon(Icons.Rounded.GTranslate, contentDescription = "Открыть в переводчике")
                            }
                        }
                    }
                }
            }
        }
    }
}

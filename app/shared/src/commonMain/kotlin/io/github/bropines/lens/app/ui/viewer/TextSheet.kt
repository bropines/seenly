package io.github.bropines.lens.app.ui.viewer

import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
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
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.foundation.layout.size
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
            val options = listOf(
                TextView.Both to stringResource(Res.string.text_view_both),
                TextView.Original to stringResource(Res.string.original),
                TextView.Translation to stringResource(Res.string.translation),
            )
            val icons = mapOf(TextView.Both to Icons.Rounded.ViewAgenda, TextView.Original to Icons.Rounded.TextFields, TextView.Translation to Icons.Rounded.Translate)
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
                ) {
                    Icon(icons.getValue(option), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = {
                services.copyText(
                    when (view) {
                        TextView.Original -> result.readableText()
                        TextView.Translation -> result.translatedText().orEmpty()
                        TextView.Both -> shareableText(result)
                    },
                )
            }) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(Res.string.copy_all)) }
            IconButton(onClick = { services.shareText(shareableText(result)) }) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(Res.string.share))
            }
        }
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(result.inReadingOrder()) { _, paragraph ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (view != TextView.Translation) {
                                    Text(
                                        paragraph.readableText(),
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
                            }) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(Res.string.copy_paragraph)) }
                            IconButton(onClick = { onTranslateWithGoogle(paragraph.readableText()) }) {
                                Icon(Icons.Rounded.GTranslate, contentDescription = stringResource(Res.string.open_in_translator))
                            }
                        }
                    }
                }
            }
        }
    }
}

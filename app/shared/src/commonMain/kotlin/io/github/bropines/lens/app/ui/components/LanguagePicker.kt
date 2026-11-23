package io.github.bropines.lens.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.Languages

/** "Auto → Русский": tap either side to change it. */
@Composable
fun LanguageBar(
    source: String?,
    target: String,
    onSource: (String?) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Over a dark image rather than the theme's surface. */
    onDark: Boolean = false,
) {
    var picking by remember { mutableStateOf<Side?>(null) }
    val content = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val colors = AssistChipDefaults.assistChipColors(labelColor = content)
    val border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = content.copy(alpha = 0.4f))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { picking = Side.Source },
            label = { Text(Languages.nameOf(source)) },
            leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null, tint = content, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            colors = colors,
            border = border,
        )
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = content.copy(alpha = 0.7f))
        AssistChip(
            onClick = { picking = Side.Target },
            label = { Text(Languages.nameOf(target)) },
            leadingIcon = { Icon(Icons.Rounded.Translate, contentDescription = null, tint = content, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            colors = colors,
            border = border,
        )
    }
    when (picking) {
        Side.Source -> LanguagePickerSheet(source, allowAuto = true, onDismiss = { picking = null }) { onSource(it); picking = null }
        Side.Target -> LanguagePickerSheet(target, allowAuto = false, onDismiss = { picking = null }) { if (it != null) onTarget(it); picking = null }
        null -> Unit
    }
}

private enum class Side { Source, Target }

@Composable
fun LanguagePickerSheet(selected: String?, allowAuto: Boolean, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        Languages.all.filter {
            query.isBlank() || it.nativeName.contains(query, true) || it.englishName.contains(query, true) || it.code.startsWith(query, true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Язык") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            if (allowAuto && query.isBlank()) {
                item { LanguageRow("Определить автоматически", null, selected == null) { onPick(null) } }
            }
            items(filtered, key = { it.code }) { language ->
                LanguageRow(language.nativeName, language.englishName, language.code == selected) { onPick(language.code) }
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, checked: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = "Выбран", tint = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

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
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** "Auto → English": tap either side to change it. */
@Composable
fun LanguageBar(
    source: String?,
    target: String,
    onSource: (String?) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Over a dark image rather than the theme's surface. */
    onDark: Boolean = false,
    /** Shares its row with other buttons: no icons, tighter padding, so the names fit. */
    compact: Boolean = false,
    /** With the source on auto: the language the text turned out to be in, shown in place of "Auto". */
    detected: String? = null,
    /** The language the translation is really in, when the second language took over from [target]. */
    actualTarget: String? = null,
) {
    val padding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp)
    var picking by remember { mutableStateOf<Side?>(null) }
    val content = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    // Two wide, tall buttons that share the row: easy targets for a thumb.
    val colors = if (onDark) {
        ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { picking = Side.Source },
            colors = colors,
            contentPadding = padding,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            // A detected language wears the "auto" mark: picking still starts from the setting.
            val shownSource = source ?: detected
            when {
                source == null && detected != null -> Icon(Icons.Rounded.AutoMode, contentDescription = stringResource(Res.string.language_detect), modifier = Modifier.size(18.dp))
                !compact -> Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(languageName(shownSource), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (compact && shownSource == source) 0.dp else 8.dp))
        }
        // With a fixed source the arrow swaps the pair; with "auto" there is nothing to swap.
        IconButton(
            onClick = { if (source != null) { onSource(actualTarget ?: target); onTarget(source) } },
            enabled = source != null,
        ) {
            Icon(
                if (source != null) Icons.Rounded.SwapHoriz else Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = if (source != null) stringResource(Res.string.language_swap) else null,
                tint = content.copy(alpha = 0.8f),
            )
        }
        FilledTonalButton(
            onClick = { picking = Side.Target },
            colors = colors,
            contentPadding = padding,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            // The second language took over: say so, with the "auto" mark, instead of claiming the setting's.
            val shownTarget = actualTarget ?: target
            when {
                actualTarget != null -> Icon(Icons.Rounded.AutoMode, contentDescription = stringResource(Res.string.language_second_used), modifier = Modifier.size(18.dp))
                !compact -> Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(languageName(shownTarget), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (compact && actualTarget == null) 0.dp else 8.dp))
        }
    }
    when (picking) {
        Side.Source -> LanguagePickerSheet(source, allowAuto = true, onDismiss = { picking = null }) { onSource(it); picking = null }
        Side.Target -> LanguagePickerSheet(target, allowAuto = false, onDismiss = { picking = null }) { if (it != null) onTarget(it); picking = null }
        null -> Unit
    }
}

private enum class Side { Source, Target }

@Composable
fun LanguagePickerSheet(
    selected: String?,
    allowAuto: Boolean,
    onDismiss: () -> Unit,
    /** What the null choice is called, when it is not "detect" ("off" for the second language). */
    noneLabel: String? = null,
    onPick: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val localized = Languages.all.associate { it.code to localizedLanguageName(it.code) }
    val filtered = remember(query, localized) {
        Languages.all.filter {
            query.isBlank() || it.nativeName.contains(query, true) || it.englishName.contains(query, true) ||
                localized.getValue(it.code).contains(query, true) || it.code.startsWith(query, true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text(stringResource(Res.string.language_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            if (allowAuto && query.isBlank()) {
                item { LanguageRow(noneLabel ?: stringResource(Res.string.language_detect), null, selected == null) { onPick(null) } }
            }
            items(filtered, key = { it.code }) { language ->
                LanguageRow(language.nativeName, localized.getValue(language.code), language.code == selected) { onPick(language.code) }
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
            { Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.language_selected), tint = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

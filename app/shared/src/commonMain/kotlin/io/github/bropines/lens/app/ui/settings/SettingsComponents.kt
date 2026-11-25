package io.github.bropines.lens.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.ui.components.AppTopBar

/*
 * What the settings pages are built from. The look follows three rules shared
 * with TailSocks: settings sit in cards on the page background
 * (MaterialTheme.shapes.large) and chips take the small corner, never a
 * literal radius; every page has the two-line AppTopBar; a choice between a
 * few named values is a row of chips, not a list of radio buttons.
 */

/** A settings page: the two-line top bar over a scrolling column of cards. */
@Composable
internal fun SettingsScaffold(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        topBar = { AppTopBar(title, subtitle, onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A group of settings on the page background, with its name in the corner when it has one. */
@Composable
internal fun SettingsCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp).animateContentSize()) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            content()
        }
    }
}

/** Small print under the cards: what a page's settings do not cover, and the like. */
@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** Rows sit on their card, so they bring no background of their own. */
@Composable
internal fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun SettingIcon(
    icon: ImageVector,
    shape: Shape = CircleShape,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(Modifier.size(40.dp).background(container, shape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** A setting that opens something: another page, a picker, a dialog. */
@Composable
internal fun Setting(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (value.isEmpty()) null else ({ Text(value) }),
        leadingContent = { SettingIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A setting that is on or off. */
@Composable
internal fun Toggle(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { SettingIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = rowColors(),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/**
 * A setting with a few named values: its title, then the values as chips in a
 * row that scrolls sideways when they do not fit (a long word, a large font).
 * [description] is a line about the value that is chosen.
 */
@Composable
internal fun <T> ChipChoice(
    icon: ImageVector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    description: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
        }
        ChipRow(options, selected, Modifier.padding(start = 72.dp, end = 16.dp), onSelect = onSelect)
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Mutually exclusive values as chips. [inset] is applied inside the scroll,
 * so the chips line up with the text above and still slide to the card's edge.
 * [trailing] is for a chip of another kind after them, in the same row.
 */
@Composable
internal fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    inset: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).then(inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, maxLines = 1) },
                shape = MaterialTheme.shapes.small,
            )
        }
        trailing()
    }
}

@Composable
internal fun SliderSetting(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        @Suppress("DEPRECATION")
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.padding(start = 56.dp))
    }
}

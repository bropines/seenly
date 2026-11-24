package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.ThemeColor
import io.github.bropines.lens.app.platform.platformDynamicColorScheme
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.theme.seed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The palette presets as a row of swatches. "Wallpaper" is offered only where
 * the system has wallpaper colours (Android 12+); elsewhere a saved
 * "Wallpaper" shows as the violet it falls back to.
 */
@Composable
internal fun ThemeColorPicker(selected: ThemeColor, onSelect: (ThemeColor) -> Unit) {
    val wallpaper = platformDynamicColorScheme(isSystemInDarkTheme())
    val shown = selected.takeUnless { it == ThemeColor.Wallpaper && wallpaper == null } ?: ThemeColor.Violet
    val options = ThemeColor.entries.filter { it != ThemeColor.Wallpaper || wallpaper != null }
    Column {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.theme_color)) },
            supportingContent = { Text(stringResource(shown.label())) },
            leadingContent = { SettingIcon(Icons.Rounded.Palette) },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEach { option ->
                val fill = if (option == ThemeColor.Wallpaper) wallpaper!!.primary else option.seed()
                Swatch(
                    fill = fill,
                    selected = option == shown,
                    label = stringResource(option.label()),
                    wallpaper = option == ThemeColor.Wallpaper,
                ) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun Swatch(fill: Color, selected: Boolean, label: String, wallpaper: Boolean, onClick: () -> Unit) {
    val mark = if (fill.luminance() > 0.5f) Color.Black else Color.White
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .padding(if (selected) 5.dp else 0.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected -> Icon(Icons.Rounded.Check, contentDescription = null, tint = mark)
            wallpaper -> Icon(Icons.Rounded.Wallpaper, contentDescription = null, tint = mark)
        }
    }
}

private fun ThemeColor.label(): StringResource = when (this) {
    ThemeColor.Wallpaper -> Res.string.color_wallpaper
    ThemeColor.Violet -> Res.string.color_violet
    ThemeColor.Blue -> Res.string.color_blue
    ThemeColor.Teal -> Res.string.color_teal
    ThemeColor.Green -> Res.string.color_green
    ThemeColor.Amber -> Res.string.color_amber
    ThemeColor.Red -> Res.string.color_red
    ThemeColor.Pink -> Res.string.color_pink
    ThemeColor.Mono -> Res.string.color_mono
}

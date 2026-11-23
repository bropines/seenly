package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/**
 * A floating bar of actions over the selected text, like Lens's: above the
 * selection when there is room, below its handles otherwise, kept on screen.
 */
@Composable
fun SelectionActions(
    bounds: Rect,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
) {
    Layout(
        content = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Action(Icons.Rounded.ContentCopy, "Копировать", onCopy)
                    Action(Icons.Rounded.Translate, "Перевести", onTranslate)
                    IconAction(Icons.Rounded.SelectAll, "Выделить всё", onSelectAll)
                    IconAction(Icons.Rounded.Share, "Поделиться", onShare)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val bar = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 12.dp.roundToPx()
        val x = (bounds.center.x - bar.width / 2f).toInt()
            .coerceIn(gap, (constraints.maxWidth - bar.width - gap).coerceAtLeast(gap))
        val above = bounds.top - bar.height - gap
        // Below, the selection's end handle hangs about 24 dp under the text.
        val y = if (above > constraints.maxHeight * 0.14f) above else bounds.bottom + 40.dp.toPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            bar.place(x, y.toInt().coerceIn(0, (constraints.maxHeight - bar.height).coerceAtLeast(0)))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary) }
}

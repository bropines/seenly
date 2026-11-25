package io.github.bropines.lens.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The menu a held (or right-clicked) page opens where it was touched: what
 * one most often wants of a page, without reaching for the panel.
 */
@Composable
internal fun QuickMenu(
    /** Where the page was held, in the viewer's pixels; null while closed. */
    at: Offset?,
    hasText: Boolean,
    hasTranslation: Boolean,
    canExport: Boolean,
    showingOriginal: Boolean,
    onDismiss: () -> Unit,
    onCopyTranslation: () -> Unit,
    onCopyOriginal: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRescan: () -> Unit,
    onToggleOriginal: () -> Unit,
) {
    // The place is kept while the menu fades out, when `at` is null already.
    var place by remember { mutableStateOf(Offset.Zero) }
    if (at != null) place = at
    Box(Modifier.offset { IntOffset(place.x.roundToInt(), place.y.roundToInt()) }.size(1.dp)) {
        DropdownMenu(expanded = at != null, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge) {
            @Composable
            fun Entry(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    enabled = enabled,
                    onClick = {
                        onDismiss()
                        onClick()
                    },
                )
            }
            Entry(Icons.Rounded.ContentCopy, stringResource(Res.string.quick_copy_translation), hasTranslation, onCopyTranslation)
            Entry(Icons.AutoMirrored.Rounded.Notes, stringResource(Res.string.quick_copy_original), hasText, onCopyOriginal)
            HorizontalDivider()
            Entry(Icons.Rounded.Share, stringResource(Res.string.quick_share), hasText, onShare)
            Entry(Icons.Rounded.Download, stringResource(Res.string.quick_save), canExport, onSave)
            HorizontalDivider()
            Entry(
                if (showingOriginal) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                stringResource(if (showingOriginal) Res.string.quick_show_translation else Res.string.quick_show_original),
                hasTranslation,
                onToggleOriginal,
            )
            Entry(Icons.Rounded.Refresh, stringResource(Res.string.viewer_rescan), onClick = onRescan)
        }
    }
}

/** What to share of a translated page: the picture, the text, or both at once. */
@Composable
internal fun ShareSheet(
    canExport: Boolean,
    onDismiss: () -> Unit,
    onImage: () -> Unit,
    onText: () -> Unit,
    onBoth: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Text(
                stringResource(Res.string.share_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            ShareRow(Icons.Rounded.Image, stringResource(Res.string.share_image), stringResource(Res.string.share_image_desc), canExport) {
                onDismiss()
                onImage()
            }
            ShareRow(Icons.Rounded.Translate, stringResource(Res.string.share_text), stringResource(Res.string.share_text_desc), true) {
                onDismiss()
                onText()
            }
            ShareRow(Icons.Rounded.AddPhotoAlternate, stringResource(Res.string.share_both), stringResource(Res.string.share_both_desc), canExport) {
                onDismiss()
                onBoth()
            }
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val fade = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = fade), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = fade)) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = fade))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade))
        }
    }
}

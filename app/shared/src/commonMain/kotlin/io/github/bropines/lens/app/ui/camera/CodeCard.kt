package io.github.bropines.lens.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.camera_password
import io.github.bropines.lens.app.resources.copy
import io.github.bropines.lens.app.resources.hide
import io.github.bropines.lens.app.resources.open
import org.jetbrains.compose.resources.stringResource

/**
 * The first QR code or barcode in an image (a photo from the gallery, a shared
 * picture), read on the device; null when there is none or the platform cannot read codes.
 */
expect suspend fun readCode(image: ByteArray): ScannedCode?

/** What a code says: open a link, copy text, join Wi-Fi by copying the password. The camera's and the viewer's. */
@Composable
internal fun CodeCard(code: ScannedCode, services: PlatformServices, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Closing is a corner ×, not a third button: three labelled buttons do not fit a phone's width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                val wifi = code.wifi
                Text(
                    if (wifi != null) "Wi-Fi: ${wifi.first}" else code.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.hide))
                }
            }
            Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (code.isUrl) {
                    Button(onClick = { services.openUrl(code.text) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(end = 6.dp))
                        Text(stringResource(Res.string.open), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val wifiPassword = code.wifi?.second
                FilledTonalButton(onClick = { services.copyText(wifiPassword ?: code.text) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(if (wifiPassword != null) Res.string.camera_password else Res.string.copy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

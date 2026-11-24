package io.github.bropines.lens.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.asString
import io.github.bropines.lens.app.update.Changelog
import io.github.bropines.lens.app.update.DownloadState
import io.github.bropines.lens.app.update.UpdateController
import io.github.bropines.lens.app.update.UpdateStatus
import org.jetbrains.compose.resources.stringResource

/**
 * The offered update: its notes, and the way to get it. Where the build can
 * replace itself that is download, verify and install; elsewhere (a debug
 * build, a store's copy, the desktop) a link to the release page.
 */
@Composable
fun UpdateDialog(updates: UpdateController) {
    val open by updates.details.collectAsState()
    val status by updates.status.collectAsState()
    val download by updates.download.collectAsState()
    val release = (status as? UpdateStatus.Available)?.release
    if (!open || release == null) return
    val groups = remember(release.notes) { Changelog.groups(release.notes) }
    val busy = download is DownloadState.Running

    AlertDialog(
        // A download keeps running behind a closed dialog; Settings shows it again.
        onDismissRequest = updates::closeDetails,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(Res.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(Res.string.update_installed, updates.installedVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (groups.isEmpty()) Text(stringResource(Res.string.update_no_notes)) else MarkdownGroups(groups)
                }
                when (val d = download) {
                    is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val progress = d.progress
                        if (progress == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(stringResource(Res.string.update_downloading), style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.NeedsPermission -> Text(stringResource(Res.string.update_needs_permission), color = MaterialTheme.colorScheme.primary)
                    is DownloadState.Failed -> Text(d.message.asString(), color = MaterialTheme.colorScheme.error)
                    is DownloadState.Ready -> Text(stringResource(Res.string.update_confirm_in_system), style = MaterialTheme.typography.bodySmall)
                    DownloadState.Idle -> if (updates.installer == null) {
                        Text(stringResource(Res.string.update_manual_only), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            val d = download
            when {
                updates.installer == null ->
                    TextButton(onClick = { updates.openReleasePage(release) }) { Text(stringResource(Res.string.update_open_page)) }
                d is DownloadState.NeedsPermission -> Row {
                    TextButton(onClick = updates::openInstallPermission) { Text(stringResource(Res.string.update_allow)) }
                    TextButton(onClick = { updates.install(d.path) }) { Text(stringResource(Res.string.update_install)) }
                }
                d is DownloadState.Ready -> TextButton(onClick = { updates.install(d.path) }) { Text(stringResource(Res.string.update_install)) }
                // A download that keeps failing still leaves the release page to get the file from.
                d is DownloadState.Failed -> Row {
                    TextButton(onClick = { updates.openReleasePage(release) }) { Text(stringResource(Res.string.update_open_page)) }
                    TextButton(onClick = updates::downloadAndInstall) { Text(stringResource(Res.string.retry)) }
                }
                else -> TextButton(onClick = updates::downloadAndInstall, enabled = !busy) { Text(stringResource(Res.string.update_download)) }
            }
        },
        dismissButton = { TextButton(onClick = updates::closeDetails) { Text(stringResource(if (busy) Res.string.hide else Res.string.update_later)) } },
    )
}

/** What changed in the installed version, from the bundled changelog. Shown once after an update and from Settings. */
@Composable
fun WhatsNewDialog(updates: UpdateController, services: PlatformServices) {
    val open by updates.whatsNew.collectAsState()
    if (!open) return
    // The app's language: Android sets the default locale from the UI language setting.
    val language = LocalLocale.current.language
    // Null until read; then the section, which is itself null when the changelog has none.
    val read by produceState<Result<Changelog.Section?>?>(null, language) { value = Result.success(updates.changelogSection(language)) }
    val section = read?.getOrNull()

    AlertDialog(
        onDismissRequest = updates::closeWhatsNew,
        icon = { Icon(Icons.Rounded.NewReleases, contentDescription = null) },
        title = {
            Column {
                Text(section?.let { stringResource(Res.string.whats_new_in, it.version) } ?: stringResource(Res.string.whats_new))
                section?.date?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                val s = section
                if (s != null) MarkdownGroups(s.groups) else if (read != null) Text(stringResource(Res.string.whats_new_unavailable))
            }
        },
        confirmButton = { TextButton(onClick = updates::closeWhatsNew) { Text(stringResource(Res.string.close)) } },
        dismissButton = {
            TextButton(onClick = { services.openUrl(Changelog.FULL_URL) }) { Text(stringResource(Res.string.whats_new_full)) }
        },
    )
}

/** Changelog groups as headed bullet lists. */
@Composable
private fun MarkdownGroups(groups: List<Changelog.Group>) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (group.title.isNotEmpty()) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                group.items.forEach { item ->
                    val text = remember(item.text, codeBackground) { inlineMarkdown(item.text, codeBackground) }
                    if (item.level == Changelog.PARAGRAPH) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(Modifier.padding(start = (item.level * 16).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (item.level == 0) "•" else "–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** The inline Markdown the changelog uses: `**bold**` and `` `code` ``. Anything else passes through. */
internal fun inlineMarkdown(text: String, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    var code = false
    val plain = StringBuilder()

    fun flush() {
        if (plain.isEmpty()) return
        val s = plain.toString()
        plain.setLength(0)
        when {
            code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(s) }
            bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s) }
            else -> append(s)
        }
    }

    while (i < text.length) {
        val c = text[i]
        when {
            // Inside a code span Markdown is literal; only the closing backtick matters.
            code && c == '`' -> { flush(); code = false; i++ }
            code -> { plain.append(c); i++ }
            c == '`' -> { flush(); code = true; i++ }
            c == '*' && text.getOrNull(i + 1) == '*' -> { flush(); bold = !bold; i += 2 }
            else -> { plain.append(c); i++ }
        }
    }
    flush()
}

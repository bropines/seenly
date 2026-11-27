package io.github.bropines.lens.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.GoogleAccount
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.history.ago
import io.github.bropines.lens.app.ui.settings.Footnote
import io.github.bropines.lens.app.ui.settings.SettingsCard
import io.github.bropines.lens.app.ui.settings.SettingsScaffold
import io.github.bropines.lens.app.ui.settings.rowColors
import io.github.bropines.lens.app.ui.settings.Setting
import io.github.bropines.lens.app.ui.settings.SettingIcon
import io.github.bropines.lens.app.ui.settings.Toggle
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The optional Google account: what connecting one does, its state, and the
 * ways in. Signing in happens in an embedded page where the platform has one
 * (Android); cookies exported from a desktop browser work everywhere, and are
 * the way through when Google refuses the embedded page.
 */
@Composable
fun AccountScreen(
    account: GoogleAccount,
    repository: SettingsRepository,
    settings: AppSettings,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by account.state.collectAsState()
    var pasting by remember { mutableStateOf(false) }
    var editingEmail by remember { mutableStateOf(false) }

    fun connect(text: String) = scope.launch {
        snackbar.showSnackbar(getString(if (account.connect(text)) Res.string.account_connected else Res.string.account_no_session))
    }
    // Any file: cookies.txt and a cookie editor's .json both come as plain text.
    val picker = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            runCatching { file.readString() }
                .onSuccess { connect(it) }
                .onFailure { snackbar.showSnackbar(getString(Res.string.settings_read_failed, it.message.orEmpty())) }
        }
    }

    val summary = stringResource(
        when {
            !state.connected -> Res.string.account_summary_none
            state.rejected -> Res.string.account_summary_rejected
            else -> Res.string.account_summary_connected
        },
    )
    SettingsScaffold(
        title = stringResource(Res.string.account_title),
        subtitle = stringResource(Res.string.settings_section_experimental),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        Footnote(
            stringResource(Res.string.account_intro) + " " +
                stringResource(if (account.encrypted) Res.string.account_storage_encrypted else Res.string.account_storage_plain),
        )
        SettingsCard {
            ListItem(
                colors = rowColors(),
                leadingContent = { SettingIcon(Icons.Rounded.AccountCircle) },
                headlineContent = { Text(summary) },
                supportingContent = if (!state.connected) null else ({
                    val used = state.lastUsed
                    Column {
                        Text(if (used == null) stringResource(Res.string.account_never_used) else stringResource(Res.string.account_last_used, ago(used)))
                        // Google's own words, selectable, so they can be copied into a bug report.
                        if (state.rejected && state.refusal.isNotEmpty()) {
                            SelectionContainer {
                                Text(state.refusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }),
            )

            if (state.connected) {
                Toggle(
                    Icons.Rounded.DocumentScanner, stringResource(Res.string.account_use_for_lens), stringResource(Res.string.account_use_for_lens_desc),
                    settings.account.useForLens,
                ) { on -> scope.launch { repository.update { it.copy(account = it.account.copy(useForLens = on)) } } }
                // Identity delegation signs with the address; found by itself after connecting, or typed.
                Setting(
                    Icons.Rounded.AlternateEmail, stringResource(Res.string.account_email),
                    state.email ?: stringResource(Res.string.account_email_none),
                ) { editingEmail = true }
                if (state.rejected) {
                    Setting(Icons.Rounded.Refresh, stringResource(Res.string.account_retry), stringResource(Res.string.account_retry_desc), account::retry)
                }
                Setting(Icons.AutoMirrored.Rounded.Logout, stringResource(Res.string.account_sign_out), stringResource(Res.string.account_sign_out_desc)) {
                    account.disconnect()
                    scope.launch { snackbar.showSnackbar(getString(Res.string.account_removed)) }
                }
            }
        }

        SettingsCard(stringResource(if (state.connected) Res.string.account_replace else Res.string.account_connect)) {
            if (googleSignInSupported) {
                Setting(Icons.AutoMirrored.Rounded.Login, stringResource(Res.string.account_sign_in), stringResource(Res.string.account_sign_in_desc), onSignIn)
            }
            Setting(Icons.Rounded.ContentPaste, stringResource(Res.string.account_paste), stringResource(Res.string.account_paste_desc)) { pasting = true }
            Setting(Icons.Rounded.FileOpen, stringResource(Res.string.account_file), stringResource(Res.string.account_file_desc)) { picker.launch() }
        }
    }

    if (editingEmail) {
        var text by remember { mutableStateOf(state.email.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingEmail = false },
            title = { Text(stringResource(Res.string.account_email)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.account_email_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("name@gmail.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val found = account.detectEmail()
                            if (found != null) text = found
                            else snackbar.showSnackbar(getString(Res.string.account_email_not_found))
                        }
                    }) { Text(stringResource(Res.string.account_email_detect)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingEmail = false
                    account.setEmail(text)
                }) { Text(stringResource(Res.string.account_email_save)) }
            },
            dismissButton = { TextButton(onClick = { editingEmail = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(Res.string.account_paste)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.account_paste_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("SID=…; HSID=…; SAPISID=…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    pasting = false
                    connect(text)
                }) { Text(stringResource(Res.string.account_connect_action)) }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** Says once that Google refused the account's session and that requests are anonymous again. */
@Composable
fun AccountRefusedDialog(account: GoogleAccount, onOpen: () -> Unit) {
    val refused by account.refused.collectAsState()
    val state by account.state.collectAsState()
    if (!refused) return
    AlertDialog(
        onDismissRequest = account::refusalShown,
        title = { Text(stringResource(Res.string.account_refused_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.account_refused))
                if (state.refusal.isNotEmpty()) {
                    SelectionContainer {
                        Text(state.refusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                account.refusalShown()
                onOpen()
            }) { Text(stringResource(Res.string.account_title)) }
        },
        dismissButton = { TextButton(onClick = account::refusalShown) { Text(stringResource(Res.string.close)) } },
    )
}

/** Whether the platform can show Google's sign-in page inside the app (Android's WebView). */
expect val googleSignInSupported: Boolean

/**
 * Google's own sign-in page, embedded. When the user has signed in, the
 * page's google.com cookies go to [onCookies], which answers whether they
 * were a session. Google refuses some embedded browsers outright; the screen
 * then says so and points at importing cookies instead.
 */
@Composable
expect fun GoogleSignInScreen(onCookies: (String) -> Boolean, onBack: () -> Unit)

package io.github.bropines.lens.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.ProxyMode
import io.github.bropines.lens.app.data.ProxyPrefs
import io.github.bropines.lens.app.net.AppNetwork
import io.github.bropines.lens.app.net.ProxyProblem
import io.github.bropines.lens.app.net.ProxyTestResult
import io.github.bropines.lens.app.net.parseProxyPort
import io.github.bropines.lens.app.net.problem
import io.github.bropines.lens.app.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The proxy for the app's own requests. What is on screen is a draft: it can
 * be tested as it stands and takes effect on "Apply", not while an address is
 * half typed, when every request in the app would fail on it.
 */
@Composable
internal fun NetworkScreen(network: AppNetwork, settings: AppSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val applied = settings.proxy
    val appliedPassword = network.passwordFor(applied)

    // Text as typed, port included: "80a" is shown as typed and reported, not silently dropped.
    var mode by rememberSaveable { mutableStateOf(applied.mode) }
    var host by rememberSaveable { mutableStateOf(applied.host) }
    var port by rememberSaveable { mutableStateOf(if (applied.port > 0) applied.port.toString() else "") }
    var username by rememberSaveable { mutableStateOf(applied.username) }
    // Not saveable: a password does not belong in the saved instance state.
    var password by remember { mutableStateOf(appliedPassword) }
    var showPassword by remember { mutableStateOf(false) }
    // The settings arrive from disk a moment after the app starts: the draft then starts over from
    // them. Only then, though: a turn of the screen must not throw away what was being typed.
    var draftOf by rememberSaveable { mutableStateOf(applied.toString()) }
    LaunchedEffect(applied) {
        if (draftOf != applied.toString()) {
            draftOf = applied.toString()
            mode = applied.mode
            host = applied.host
            port = if (applied.port > 0) applied.port.toString() else ""
            username = applied.username
            password = appliedPassword
        }
    }

    val server = mode == ProxyMode.Http || mode == ProxyMode.Socks5
    // Without a server only the mode is a setting; the address stays as it was, for switching back.
    val draft = if (server) ProxyPrefs(mode, host.trim(), parseProxyPort(port), username.trim()) else applied.copy(mode = mode)
    val draftPassword = if (server) password else appliedPassword
    val problem = draft.problem()
    val changed = draft != applied || draftPassword != appliedPassword
    // Errors are shown once there is something to complain about, not on a form just opened.
    var complain by rememberSaveable { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProxyTestResult?>(null) }
    // A result is about the settings it was made with.
    LaunchedEffect(draft, draftPassword) { result = null }

    fun test() {
        complain = true
        if (problem != null) return
        testing = true
        scope.launch {
            result = network.test(draft, draftPassword)
            testing = false
        }
    }

    SettingsScaffold(
        title = stringResource(Res.string.network_title),
        subtitle = proxySummary(applied),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        StatusCard(draft, changed, testing, result, onTest = ::test)

        SettingsCard(stringResource(Res.string.network_proxy)) {
            ChipRow(
                listOf(
                    ProxyMode.None to stringResource(Res.string.network_mode_none),
                    ProxyMode.System to stringResource(Res.string.network_mode_system),
                    ProxyMode.Http to "HTTP",
                    ProxyMode.Socks5 to "SOCKS5",
                ),
                mode,
                Modifier.padding(horizontal = 16.dp),
            ) { mode = it }
            Text(
                stringResource(
                    when (mode) {
                        ProxyMode.None -> Res.string.network_mode_none_desc
                        ProxyMode.System -> Res.string.network_mode_system_desc
                        ProxyMode.Http -> Res.string.network_mode_http_desc
                        ProxyMode.Socks5 -> Res.string.network_mode_socks_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )

            AnimatedVisibility(server) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val hostProblem = problem.takeIf { complain && (it == ProxyProblem.NoHost || it == ProxyProblem.BadHost) }
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(Res.string.network_host)) },
                            placeholder = { Text("proxy.example.com") },
                            isError = hostProblem != null,
                            supportingText = hostProblem?.let { { Text(problemText(it)) } },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        val portProblem = problem.takeIf { complain && it == ProxyProblem.BadPort }
                        OutlinedTextField(
                            value = port,
                            // Digits only, and no longer than a port can be.
                            onValueChange = { typed -> port = typed.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(Res.string.network_port)) },
                            placeholder = { Text(if (mode == ProxyMode.Http) "8080" else "1080") },
                            isError = portProblem != null,
                            supportingText = portProblem?.let { { Text(problemText(it)) } },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.width(112.dp),
                        )
                    }
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(Res.string.network_username)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(Res.string.network_password)) },
                        // A password with no user name is not sent anywhere; say so by greying the field.
                        enabled = username.isNotBlank(),
                        supportingText = {
                            Text(stringResource(if (network.encrypted) Res.string.network_password_encrypted else Res.string.network_password_plain))
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(if (showPassword) Res.string.network_password_hide else Res.string.network_password_show),
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Button(
            onClick = {
                complain = true
                if (problem == null) {
                    scope.launch {
                        network.save(draft, draftPassword)
                        snackbar.showSnackbar(getString(Res.string.network_applied))
                    }
                }
            },
            enabled = changed,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.network_apply)) }

        Footnote(stringResource(Res.string.network_footnote))
    }
}

@Composable
private fun problemText(problem: ProxyProblem): String = stringResource(
    when (problem) {
        ProxyProblem.NoHost -> Res.string.network_error_host
        ProxyProblem.BadHost -> Res.string.network_error_bad_host
        ProxyProblem.BadPort -> Res.string.network_error_port
    },
)

/**
 * What the settings on screen come to, and the button that tries them. The
 * card takes the colour of the last answer: plain before a test, the primary
 * container when the request came back, the error container when it did not.
 */
@Composable
private fun StatusCard(draft: ProxyPrefs, changed: Boolean, testing: Boolean, result: ProxyTestResult?, onTest: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val failed = result is ProxyTestResult.Failed || result is ProxyTestResult.Unusable
    val container by animateColorAsState(
        when {
            failed -> scheme.errorContainer
            result is ProxyTestResult.Ok -> scheme.primaryContainer
            else -> scheme.surfaceContainerHigh
        },
    )
    val content by animateColorAsState(
        when {
            failed -> scheme.onErrorContainer
            result is ProxyTestResult.Ok -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        },
    )
    Surface(shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    when {
                        failed -> Icons.Rounded.ErrorOutline
                        result is ProxyTestResult.Ok -> Icons.Rounded.CheckCircle
                        draft.mode == ProxyMode.None -> Icons.Rounded.Public
                        else -> Icons.Rounded.VpnLock
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (draft.problem() == null) proxySummary(draft) else stringResource(Res.string.network_incomplete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(if (changed) Res.string.network_not_applied else Res.string.network_in_use),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when (result) {
                is ProxyTestResult.Ok -> Text(stringResource(Res.string.network_test_ok, result.millis.toInt()), style = MaterialTheme.typography.bodyMedium)
                // The proxy's or the system's own words, selectable, so they can be copied into a search or a bug report.
                is ProxyTestResult.Failed -> SelectionContainer {
                    Text(stringResource(Res.string.network_test_failed, result.reason), style = MaterialTheme.typography.bodyMedium)
                }
                is ProxyTestResult.Unusable -> Text(problemText(result.problem), style = MaterialTheme.typography.bodyMedium)
                null -> Text(stringResource(Res.string.network_test_hint), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onTest, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.network_testing), modifier = Modifier.padding(start = 12.dp))
                } else {
                    Text(stringResource(Res.string.network_test))
                }
            }
        }
    }
}

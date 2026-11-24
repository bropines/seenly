package io.github.bropines.lens.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.TextTranslator
import io.github.bropines.lens.app.data.TranslatorPrefs
import io.github.bropines.lens.translate.OptionSpec
import io.github.bropines.lens.translate.OptionType
import io.github.bropines.lens.translate.TranslationProvider
import kotlinx.coroutines.launch
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The text translators: which are on, in what order they are tried, and each
 * one's keys and options (from its own declarative option list).
 */
@Composable
fun TranslatorsScreen(repository: SettingsRepository, settings: AppSettings, translator: TextTranslator, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = settings.translators
    fun update(transform: (TranslatorPrefs) -> TranslatorPrefs) = scope.launch {
        repository.update { it.copy(translators = transform(it.translators)) }
    }
    var editing by remember { mutableStateOf<TranslationProvider?>(null) }
    val all = translator.registry.all
    val enabled = prefs.enabled.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val disabled = all.filter { it.id !in prefs.enabled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.translators)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(Res.string.translators_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(Res.string.translators_failover)) },
                supportingContent = { Text(stringResource(Res.string.translators_failover_desc)) },
                trailingContent = { Switch(checked = prefs.failOver, onCheckedChange = { on -> update { it.copy(failOver = on) } }) },
            )

            SectionTitle(stringResource(Res.string.translators_enabled))
            enabled.forEachIndexed { index, provider ->
                EngineRow(
                    provider = provider,
                    on = true,
                    needsSetup = translator.needsSetup(provider),
                    onToggle = { update { it.copy(enabled = it.enabled - provider.id) } },
                    onClick = { editing = provider },
                    onUp = if (index > 0) ({ update { it.copy(enabled = it.enabled.move(index, index - 1)) } }) else null,
                    onDown = if (index < enabled.lastIndex) ({ update { it.copy(enabled = it.enabled.move(index, index + 1)) } }) else null,
                )
            }
            if (disabled.isNotEmpty()) SectionTitle(stringResource(Res.string.translators_available))
            disabled.forEach { provider ->
                EngineRow(
                    provider = provider,
                    on = false,
                    needsSetup = provider.needsCredentials && translator.needsSetup(provider),
                    onToggle = { update { it.copy(enabled = it.enabled + provider.id) } },
                    onClick = { editing = provider },
                    onUp = null,
                    onDown = null,
                )
            }
        }
    }

    editing?.let { provider ->
        EngineOptionsSheet(
            provider = provider,
            values = prefs.options[provider.id].orEmpty(),
            onChange = { key, value ->
                update { p ->
                    val current = p.options[provider.id].orEmpty()
                    p.copy(options = p.options + (provider.id to (current + (key to value))))
                }
            },
            onDismiss = { editing = null },
        )
    }
}

private fun List<String>.move(from: Int, to: Int): List<String> = toMutableList().apply { add(to, removeAt(from)) }

/** One line per engine in the app's language; the providers' own descriptions are English. */
private val DESCRIPTIONS: Map<String, StringResource> = mapOf(
    "google" to Res.string.engine_google,
    "bing" to Res.string.engine_bing,
    "yandex" to Res.string.engine_yandex,
    "deepl-web" to Res.string.engine_deepl_web,
    "lara" to Res.string.engine_lara,
    "modernmt" to Res.string.engine_modernmt,
    "mymemory" to Res.string.engine_mymemory,
    "lingva" to Res.string.engine_lingva,
    "deepl" to Res.string.engine_deepl,
    "deeplx" to Res.string.engine_deeplx,
    "yandex-cloud" to Res.string.engine_yandex_cloud,
    "libretranslate" to Res.string.engine_libretranslate,
    "openai" to Res.string.engine_openai,
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun EngineRow(
    provider: TranslationProvider,
    on: Boolean,
    needsSetup: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(provider.name) },
        supportingContent = {
            Text(
                when {
                    needsSetup -> stringResource(Res.string.translators_needs_setup)
                    DESCRIPTIONS[provider.id] != null -> stringResource(DESCRIPTIONS.getValue(provider.id))
                    provider.description.isNotBlank() -> provider.description
                    provider.needsCredentials -> stringResource(Res.string.translators_with_key)
                    else -> stringResource(Res.string.translators_no_key)
                },
                color = if (needsSetup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        },
        leadingContent = {
            Icon(
                if (provider.needsCredentials) Icons.Rounded.Key else Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (on) {
                    IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(Res.string.move_up)) }
                    IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(Res.string.move_down)) }
                }
                Switch(checked = on, onCheckedChange = { onToggle() })
            }
        },
        modifier = Modifier.clickable(enabled = provider.options.isNotEmpty(), onClick = onClick),
    )
}

/** The engine's own options, rendered from its [OptionSpec] list: no engine writes UI. */
@Composable
private fun EngineOptionsSheet(
    provider: TranslationProvider,
    values: Map<String, String>,
    onChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(provider.name, style = MaterialTheme.typography.titleLarge)
            if (provider.options.isEmpty()) Text(stringResource(Res.string.translators_no_options))
            provider.options.forEach { spec -> OptionField(spec, values[spec.key] ?: spec.default) { onChange(spec.key, it) } }
        }
    }
}

@Composable
private fun OptionField(spec: OptionSpec, value: String, onChange: (String) -> Unit) {
    val label = spec.label + if (spec.required) " *" else ""
    when (spec.type) {
        OptionType.Boolean -> ListItem(
            headlineContent = { Text(label) },
            supportingContent = spec.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            trailingContent = { Switch(checked = value.toBoolean(), onCheckedChange = { onChange(it.toString()) }) },
        )
        OptionType.Select -> Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            spec.choices.forEach { (choice, title) ->
                Row(Modifier.fillMaxWidth().clickable { onChange(choice) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == choice, onClick = { onChange(choice) })
                    Text(title)
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            supportingText = spec.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = if (spec.type == OptionType.Secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (spec.type) {
                    OptionType.Url -> KeyboardType.Uri
                    OptionType.Number -> KeyboardType.Number
                    OptionType.Secret -> KeyboardType.Password
                    else -> KeyboardType.Text
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

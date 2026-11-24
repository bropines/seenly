package io.github.bropines.lens.app.ui.ondevice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.OnDevicePrefs
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.ondevice.OcrScript
import io.github.bropines.lens.app.ondevice.OnDeviceMl
import io.github.bropines.lens.app.ondevice.OnDeviceModel
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.components.localizedLanguageName
import io.github.bropines.lens.app.ui.settings.Section
import io.github.bropines.lens.app.ui.settings.Toggle
import io.github.bropines.lens.translate.Languages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * On-device scanning (the `google` flavor's ML Kit): when it is used, and the
 * models it needs. Translation languages are downloaded here or on first use
 * and can be deleted; recognizers belong to Google Play services, which
 * fetches them and keeps them.
 */
@Composable
fun OnDeviceScreen(repository: SettingsRepository, settings: AppSettings, ml: OnDeviceMl, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun update(transform: (OnDevicePrefs) -> OnDevicePrefs) = scope.launch { repository.update { it.copy(onDevice = transform(it.onDevice)) } }

    var models by remember { mutableStateOf<List<OnDeviceModel>?>(null) }
    // Downloads in flight, by model: the fraction done, or null while the platform does not say.
    var downloading by remember { mutableStateOf<Map<OnDeviceModel, Float?>>(emptyMap()) }
    suspend fun reload() {
        models = runCatching { ml.models() }.getOrElse { emptyList() }
    }
    LaunchedEffect(ml) { reload() }

    fun download(model: OnDeviceModel) = scope.launch {
        downloading = downloading + (model to null)
        try {
            ml.download(model, settings.onDevice.wifiOnly) { progress -> downloading = downloading + (model to progress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snackbar.showSnackbar(getString(Res.string.on_device_download_failed, e.message.orEmpty()))
        } finally {
            downloading = downloading - model
            reload()
        }
    }

    fun delete(model: OnDeviceModel) = scope.launch {
        runCatching { ml.delete(model) }
        reload()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.on_device_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(Res.string.on_device_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            val prefs = settings.onDevice
            Toggle(
                Icons.Rounded.CloudOff, stringResource(Res.string.on_device_fallback), stringResource(Res.string.on_device_fallback_desc),
                prefs.fallback,
            ) { on -> update { it.copy(fallback = on) } }
            Toggle(
                Icons.Rounded.Shield, stringResource(Res.string.on_device_always), stringResource(Res.string.on_device_always_desc),
                prefs.always,
            ) { on -> update { it.copy(always = on) } }
            Toggle(
                Icons.Rounded.Wifi, stringResource(Res.string.on_device_wifi), stringResource(Res.string.on_device_wifi_desc),
                prefs.wifiOnly,
            ) { on -> update { it.copy(wifiOnly = on) } }

            val all = models
            if (all == null) {
                CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp))
                return@Column
            }

            Section(stringResource(Res.string.on_device_recognizers))
            all.filter { it.kind == OnDeviceModel.Kind.Recognizer }.forEach { model ->
                ModelRow(model, scriptName(model.id), downloading, ::download, ::delete)
            }

            Section(stringResource(Res.string.on_device_languages))
            val languages = all.filter { it.kind == OnDeviceModel.Kind.Translation }.map { it to modelLanguageName(it.id) }
            // What is on the device first: that is the list a person comes to check or trim.
            languages.sortedWith(compareByDescending<Pair<OnDeviceModel, String>> { it.first.downloaded }.thenBy { it.second })
                .forEach { (model, name) -> ModelRow(model, name, downloading, ::download, ::delete) }
        }
    }
}

@Composable
private fun ModelRow(
    model: OnDeviceModel,
    name: String,
    downloading: Map<OnDeviceModel, Float?>,
    onDownload: (OnDeviceModel) -> Unit,
    onDelete: (OnDeviceModel) -> Unit,
) {
    val busy = model in downloading
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                when {
                    busy -> stringResource(Res.string.on_device_downloading)
                    model.downloaded && !model.removable -> stringResource(Res.string.on_device_built_in)
                    model.downloaded -> stringResource(Res.string.on_device_downloaded)
                    model.approxBytes != null -> stringResource(Res.string.on_device_size, (model.approxBytes / 1_000_000).toInt())
                    else -> stringResource(Res.string.on_device_not_downloaded)
                },
            )
        },
        trailingContent = {
            when {
                busy -> {
                    val progress = downloading[model]
                    if (progress == null) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                }
                !model.downloaded -> IconButton(onClick = { onDownload(model) }) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(Res.string.on_device_download))
                }
                model.removable -> IconButton(onClick = { onDelete(model) }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(Res.string.delete))
                }
                else -> Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** A translation model's language in the UI language where the app has that name, else in English. */
@Composable
private fun modelLanguageName(tag: String): String =
    localizedLanguageName(tag).takeIf { it != tag } ?: Languages.name(tag)

@Composable
private fun scriptName(id: String): String = stringResource(
    when (OcrScript.entries.firstOrNull { it.name == id }) {
        OcrScript.Chinese -> Res.string.script_chinese
        OcrScript.Japanese -> Res.string.script_japanese
        OcrScript.Korean -> Res.string.script_korean
        OcrScript.Devanagari -> Res.string.script_devanagari
        else -> Res.string.script_latin
    },
)

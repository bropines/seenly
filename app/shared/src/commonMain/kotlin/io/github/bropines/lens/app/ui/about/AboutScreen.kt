package io.github.bropines.lens.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** Version shown in About; keep in step with versionName in app/android. */

/** What the app is, whose it is not, what it sends where, and the open-source licences. */
@Composable
fun AboutScreen(services: PlatformServices, onBack: () -> Unit) {
    var licences by remember { mutableStateOf(false) }
    if (licences) {
        LicencesScreen(onBack = { licences = false })
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.about_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Block(Icons.Rounded.Info, stringResource(Res.string.about_version, services.appVersion)) {
                Text(stringResource(Res.string.about_intro))
            }
            Block(Icons.Rounded.Gavel, stringResource(Res.string.about_unofficial)) {
                Text(stringResource(Res.string.about_unofficial_1))
                Text(stringResource(Res.string.about_unofficial_2))
            }
            Block(Icons.Rounded.Lock, stringResource(Res.string.about_data)) {
                Text(stringResource(Res.string.about_data_1))
                Text(stringResource(Res.string.about_data_2))
                Text(stringResource(Res.string.about_data_3))
                Text(stringResource(Res.string.about_data_4))
                Text(stringResource(Res.string.about_data_5))
                Text(stringResource(Res.string.about_data_6))
            }
            Block(Icons.Rounded.Code, stringResource(Res.string.about_source)) {
                Text(stringResource(Res.string.about_author))
                FilledTonalButton(onClick = { licences = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.about_licences_button))
                }
                FilledTonalButton(onClick = { services.openUrl("https://github.com/bropines") }, modifier = Modifier.fillMaxWidth()) {
                    Text("github.com/bropines")
                }
            }
        }
    }
}

@Composable
private fun Block(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}

@Composable
private fun LicencesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries { Res.readBytes("files/aboutlibraries.json").decodeToString() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.licences_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        LibrariesContainer(libraries, Modifier.fillMaxSize().padding(padding))
    }
}

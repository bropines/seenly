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
import io.github.bropines.lens.app.resources.Res

/** Version shown in About; keep in step with versionName in app/android. */
const val APP_VERSION = "0.1.0"

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
                title = { Text("О приложении") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Block(Icons.Rounded.Info, "Lens · версия $APP_VERSION") {
                Text(
                    "Перевод и распознавание текста на картинках, скриншотах и фото с камеры. " +
                        "Открытый проект на Kotlin Multiplatform.",
                )
            }
            Block(Icons.Rounded.Gavel, "Неофициальное приложение") {
                Text(
                    "Это независимый проект. Он не связан с Google LLC, не одобрен и не поддерживается ею. " +
                        "Google, Google Lens и Google Translate — товарные знаки Google LLC; здесь они названы только " +
                        "чтобы описать, с какими сервисами работает приложение.",
                )
                Text(
                    "Распознавание, перевод и поиск выполняют сервисы Google и другие переводчики, которые вы включили. " +
                        "Их работа, доступность и условия не зависят от этого приложения и могут измениться в любой момент.",
                )
            }
            Block(Icons.Rounded.Lock, "Что куда отправляется") {
                Text("• Картинка, которую вы открыли или сняли, отправляется в Google Lens для распознавания и перевода.")
                Text("• Текст, который вы переводите отдельно, — в выбранный переводчик.")
                Text("• Результаты поиска открываются в вашем браузере, со своими cookies и настройками.")
                Text("• История хранится только на этом устройстве; у приложения нет своих серверов и аналитики.")
                Text(
                    "• Служба спецвозможностей Lens (если вы её включили) не читает окна и не следит за действиями: " +
                        "она только рисует перевод экрана поверх других приложений.",
                )
                Text("• Не открывайте здесь то, чем не готовы поделиться с этими сервисами.")
            }
            Block(Icons.Rounded.Code, "Исходный код и лицензии") {
                Text("Автор: bropines. Приложение использует открытые библиотеки — их лицензии ниже.")
                FilledTonalButton(onClick = { licences = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Лицензии открытых библиотек")
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
                title = { Text("Лицензии") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        LibrariesContainer(libraries, Modifier.fillMaxSize().padding(padding))
    }
}

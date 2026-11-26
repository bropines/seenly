package io.github.bropines.lens.app.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.app.resources.Res
import io.github.bropines.lens.app.resources.update_error
import io.github.bropines.lens.app.resources.update_error_checksum
import io.github.bropines.lens.app.resources.update_error_no_file
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.uiText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/** What the last check for updates found. */
sealed interface UpdateStatus {
    data object Unknown : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    data class Failed(val message: UiText) : UpdateStatus
}

/** Where the offered update's APK is on its way to the installer. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** [progress] is 0..1, or null while the size is unknown. */
    data class Running(val progress: Float?) : DownloadState

    /** Downloaded and verified, but the user has not allowed this app to install packages. */
    data class NeedsPermission(val path: String) : DownloadState

    /** Handed to the system's installer; kept so "Install" can hand it over again. */
    data class Ready(val path: String) : DownloadState
    data class Failed(val message: UiText) : DownloadState
}

/**
 * The in-app updater and "What's new": checks GitHub for a newer release on
 * start (at most once in [CHECK_EVERY], if the setting allows) and on demand,
 * downloads and verifies its APK, and hands it to the platform's installer.
 *
 * What it remembers between runs (when it last asked, which version the user
 * waved away, which changelog was shown) is state, not a preference, so it has
 * its own store and stays out of the settings export.
 */
@OptIn(ExperimentalTime::class)
@SingleIn(AppScope::class)
@Inject
class UpdateController(
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val services: PlatformServices,
) {
    private val log = Logger.withTag("Updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { "${services.dataDir}/update-state.preferences_pb".toPath() },
    )
    private val downloader = UpdateDownloader(http)

    /** Null where an update can only be linked to (debug builds, store installs, the desktop). */
    val installer: UpdateInstaller? get() = services.updateInstaller

    val installedVersion: String get() = services.appVersion

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _prompt = MutableStateFlow<Release?>(null)
    /** An update the start-up check found and the user has not yet answered: shown as a snackbar. */
    val prompt: StateFlow<Release?> = _prompt.asStateFlow()

    private val _details = MutableStateFlow(false)
    /** Whether the update dialog (notes, download, install) is open. */
    val details: StateFlow<Boolean> = _details.asStateFlow()

    private val _whatsNew = MutableStateFlow(false)
    /** Whether "What's new" is open: once after an update, or from Settings. */
    val whatsNew: StateFlow<Boolean> = _whatsNew.asStateFlow()

    private var started = false
    private var checking: Job? = null
    private var downloading: Job? = null

    /** Called when the UI appears. Does its work once per process, so a recreated activity asks nothing again. */
    fun onAppStart() {
        if (started) return
        started = true
        scope.launch {
            announceUpdate()
            val prefs = settings.settings.first().updates
            val last = store.data.first()[LAST_CHECK] ?: 0
            val now = Clock.System.now().toEpochMilliseconds()
            // A clock set back must not silence the check for good.
            if (prefs.autoCheck && (now - last >= CHECK_EVERY.inWholeMilliseconds || now < last)) check(manual = false)
        }
    }

    /** Shows "What's new" once after the app was updated to a version it has not been shown for. */
    private suspend fun announceUpdate() {
        val current = Changelog.seenKey(installedVersion)
        val seen = store.data.first()[SEEN_CHANGELOG]
        when {
            seen == current -> Unit
            // Nothing recorded: a fresh install has nothing new to hear about, an update from
            // a version before this feature does.
            seen == null && !services.wasUpdated -> markChangelogSeen()
            else -> _whatsNew.value = true
        }
    }

    /** Asks GitHub now. A [manual] check reports "up to date" and errors; the one on start stays quiet. */
    fun check(manual: Boolean = true) {
        if (checking?.isActive == true) return
        checking = scope.launch {
            val before = _status.value
            _status.value = UpdateStatus.Checking
            try {
                val betas = settings.settings.first().updates.beta
                val offered = UpdateCheck.offer(installedVersion, UpdateCheck.fetch(http), betas)
                store.edit { it[LAST_CHECK] = Clock.System.now().toEpochMilliseconds() }
                if (offered == null) {
                    _status.value = UpdateStatus.UpToDate
                    return@launch
                }
                if ((before as? UpdateStatus.Available)?.release?.tag != offered.tag) _download.value = DownloadState.Idle
                _status.value = UpdateStatus.Available(offered)
                when {
                    manual -> _details.value = true
                    store.data.first()[DISMISSED] != offered.tag -> _prompt.value = offered
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "update check failed" }
                // A failed background check leaves what was known; only a person who asked is told.
                _status.value = if (manual) UpdateStatus.Failed(uiText(Res.string.update_error, e.message.orEmpty())) else before
            }
        }
    }

    /** Opens the dialog for the offered update; the snackbar has done its job. */
    fun showDetails() {
        _prompt.value = null
        _details.value = true
    }

    fun closeDetails() {
        _details.value = false
    }

    /** "Later": this version is not announced again on start. Settings still offers it. */
    fun dismissPrompt() {
        val release = _prompt.value ?: return
        _prompt.value = null
        scope.launch { store.edit { it[DISMISSED] = release.tag } }
    }

    /** Downloads the offered update, verifies it and hands it to the installer. */
    fun downloadAndInstall() {
        val release = (_status.value as? UpdateStatus.Available)?.release ?: return
        val installer = installer ?: return
        if (downloading?.isActive == true) return
        downloading = scope.launch {
            _download.value = DownloadState.Running(null)
            try {
                val path = downloader.fetch(release, installer.flavor, installer.downloadDir) { _download.value = DownloadState.Running(it) }
                install(path, askPermission = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "update download failed" }
                _download.value = DownloadState.Failed(
                    when {
                        e is UpdateException && "checksum" in e.message.orEmpty() -> uiText(Res.string.update_error_checksum)
                        e is UpdateException && UpdateCheck.apkFor(release, installer.flavor) == null -> uiText(Res.string.update_error_no_file)
                        else -> uiText(Res.string.update_error, e.message.orEmpty())
                    },
                )
            }
        }
    }

    /**
     * Hands a verified download to the installer. Without the permission to
     * install apps, [askPermission] opens the system page for it at once, and
     * [onResumed] finishes the install when the person comes back with it
     * granted. Also the "Install" button.
     */
    fun install(path: String, askPermission: Boolean = false) {
        val installer = installer ?: return
        if (!installer.canInstall()) {
            _download.value = DownloadState.NeedsPermission(path)
            if (askPermission) runCatching { installer.openInstallPermission() }.onFailure { log.w(it) { "permission page did not open" } }
            return
        }
        _download.value = DownloadState.Ready(path)
        runCatching { installer.install(path) }.onFailure {
            log.w(it) { "installer did not start" }
            _download.value = DownloadState.Failed(uiText(Res.string.update_error, it.message.orEmpty()))
        }
    }

    fun openInstallPermission() {
        installer?.openInstallPermission()
    }

    /** The app is in front again: an install that waited for the permission goes on once it is granted. */
    fun onResumed() {
        val waiting = _download.value as? DownloadState.NeedsPermission ?: return
        if (installer?.canInstall() == true) install(waiting.path)
    }

    fun openReleasePage(release: Release) = services.openUrl(UpdateCheck.page(release.tag))

    fun showWhatsNew() {
        _whatsNew.value = true
    }

    fun closeWhatsNew() {
        _whatsNew.value = false
        scope.launch { markChangelogSeen() }
    }

    private suspend fun markChangelogSeen() {
        store.edit { it[SEEN_CHANGELOG] = Changelog.seenKey(installedVersion) }
    }

    /** The bundled changelog's section for this build, in [language] where it has one. */
    suspend fun changelogSection(language: String): Changelog.Section? = runCatching {
        val english = Changelog.parse(Res.readBytes(Changelog.FILE).decodeToString())
        val translated = if (language == "ru") Changelog.parse(Res.readBytes(Changelog.FILE_RU).decodeToString()) else emptyList()
        Changelog.pick(english, translated, installedVersion)
    }.onFailure { log.w(it) { "changelog not read" } }.getOrNull()

    private companion object {
        val CHECK_EVERY = 12.hours
        val LAST_CHECK = longPreferencesKey("last_check")
        val DISMISSED = stringPreferencesKey("dismissed_update")
        val SEEN_CHANGELOG = stringPreferencesKey("seen_changelog")
    }
}

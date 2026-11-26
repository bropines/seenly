package io.github.bropines.lens.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Gallery
import io.github.bropines.lens.app.data.PageScanner
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.ViewerLibrary
import io.github.bropines.lens.app.data.ViewMode
import io.github.bropines.lens.app.data.GalleryOrigin
import io.github.bropines.lens.app.data.history.PagePlace
import io.github.bropines.lens.app.ondevice.OnDeviceException
import io.github.bropines.lens.app.platform.quarterTurns
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.uiText
import io.github.bropines.lens.protocol.DetectedObject
import io.github.bropines.lens.protocol.LensException
import io.github.bropines.lens.protocol.LensSearchSession
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether [e] says the network could not be reached at all. */
private fun isOffline(e: Throwable): Boolean {
    val name = e::class.simpleName.orEmpty()
    return "UnknownHost" in name || "Connect" in name || "Socket" in name || "UnresolvedAddress" in name
}

/** A message a person can act on, instead of an exception's text. */
internal fun friendlyError(e: Throwable): UiText {
    val name = e::class.simpleName.orEmpty()
    return when {
        "Security" in name || "Permission" in name -> uiText(Res.string.error_no_file_access)
        isOffline(e) -> uiText(Res.string.error_no_connection)
        "Timeout" in name -> uiText(Res.string.error_timeout)
        "FileNotFound" in name || "NoSuch" in name -> uiText(Res.string.error_file_not_found)
        else -> uiText(Res.string.error_generic, e.message ?: name)
    }
}

/** Lens's own message when it gave one. */
private fun lensError(e: LensException): UiText = e.message?.let(UiText::Plain) ?: uiText(Res.string.error_lens)

/** Why the device could not scan, in terms of what to do about it. */
private fun onDeviceError(e: OnDeviceException): UiText = when (e.reason) {
    OnDeviceException.Reason.ModelMissing -> uiText(Res.string.error_on_device_model)
    OnDeviceException.Reason.Unsupported -> uiText(Res.string.error_on_device_unsupported)
    OnDeviceException.Reason.Failed -> uiText(Res.string.error_on_device, e.message.orEmpty())
}

/** What kind of trouble a failed scan was, for the icon and for what is worth trying next. */
enum class FailKind {
    /** No way to Lens: the network is down. */
    Offline,

    /** Lens asked to slow down. */
    RateLimited,
    Other,
}

sealed interface PageState {
    data object Loading : PageState

    /** [offline]: scanned on the device, not by Lens, so the page says so. */
    data class Ready(val result: ScanResult, val offline: Boolean = false) : PageState
    data class Failed(val message: UiText, val kind: FailKind = FailKind.Other) : PageState
}

/**
 * The pages of one gallery as the viewer shows them.
 *
 * The visible page is scanned first; the next [preload] pages and the one
 * before follow one at a time, so a swipe lands on a page that is ready. Only
 * the pages around the visible one are kept here: the scanner remembers the
 * rest, and a 200-page chapter costs no more memory than a short one.
 */
class ViewerViewModel(
    val gallery: Gallery,
    private val scans: PageScanner,
    private val settingsRepository: SettingsRepository,
    /** What is kept between sessions; null where nothing is (previews). */
    private val library: ViewerLibrary? = null,
    /** How many pages past the visible one are scanned ahead of the reader. */
    private val preload: Int = PRELOAD,
) : ViewModel() {
    // Starts from the settings already read, not the defaults: a first scan in the default
    // language would be a request thrown away a moment later.
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.current.value)

    private val _pages = MutableStateFlow<Map<Int, PageState>>(emptyMap())

    /** The pages around the visible one; a page missing here has not been asked for, or was let go. */
    val pages: StateFlow<Map<Int, PageState>> = _pages.asStateFlow()

    private val jobs = mutableMapOf<Int, Job>()
    private var preloading: Job? = null
    private var visible = gallery.startIndex.coerceIn(0, (gallery.items.size - 1).coerceAtLeast(0))

    private val _stale = MutableStateFlow<Set<Int>>(emptySet())
    /** Pages still showing the previous language's result while the new one is fetched. */
    val stale: StateFlow<Set<Int>> = _stale.asStateFlow()

    private val _failed = MutableStateFlow<Set<Int>>(emptySet())
    /** Pages whose last scan failed, also once they are out of [pages]: the grid marks them. */
    val failed: StateFlow<Set<Int>> = _failed.asStateFlow()

    private val _done = MutableStateFlow<Set<Int>>(emptySet())
    /** Pages scanned in this session, also once they are out of [pages]. */
    val done: StateFlow<Set<Int>> = _done.asStateFlow()

    /** Keys of the pictures that have a stored translation into the current language, from any session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val translated: StateFlow<Set<String>> =
        (library?.let { kept -> settings.map { it.targetLanguage }.distinctUntilChanged().flatMapLatest { kept.translatedKeys(it) } } ?: flowOf(emptySet()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // A new language invalidates every page. The old translation stays on
        // screen until the new one arrives, so the change is seen as it lands,
        // not as a blank page.
        //
        // This must follow `settings` itself, not the repository flow: ensure()
        // reads settings.value, and a second subscription could fire before
        // this StateFlow has the new value - rescanning in the old language.
        viewModelScope.launch {
            settings
                .map { Triple(it.targetLanguage, it.sourceLanguage, it.ocrLanguage) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    val shown = _pages.value.filterValues { it is PageState.Ready }
                    _pages.value = shown
                    _stale.value = shown.keys
                    _failed.value = emptySet()
                    _done.value = emptySet()
                    onPageVisible(visible)
                }
        }
    }

    /**
     * The reader settled on page [index]: it is scanned, its neighbours after
     * it, pages far behind are let go, and the place is remembered. Not for a
     * page merely passed while scrubbing: that would ask for every page on the way.
     */
    fun onPageVisible(index: Int) {
        if (index !in gallery.items.indices) return
        visible = index
        letGo()
        ensure(index)
        preloadAround(index)
        library?.let { kept -> viewModelScope.launch { runCatching { kept.savePosition(gallery, index) } } }
    }

    /** The pages kept: a couple behind the reader, the visible one, and those scanned ahead. */
    private fun window(): IntRange = (visible - BEHIND)..(visible + preload.coerceAtLeast(1) + 1)

    private fun letGo() {
        val keep = window()
        jobs.keys.filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
        if (_pages.value.keys.any { it !in keep }) _pages.update { pages -> pages.filterKeys { it in keep } }
        if (_stale.value.any { it !in keep }) _stale.update { stale -> stale.filterTo(mutableSetOf()) { it in keep } }
    }

    private fun preloadAround(index: Int) {
        preloading?.cancel()
        if (preload <= 0) return
        preloading = viewModelScope.launch {
            // The page being looked at has the network first; the rest queue up behind it.
            jobs[index]?.join()
            if (_pages.value[index] is PageState.Failed) return@launch
            for (next in (1..preload).map { index + it } + (index - 1)) {
                if (next !in gallery.items.indices) continue
                ensure(next)
                jobs[next]?.join()
                // Offline or told to wait: asking for more pages would only make it worse.
                if (_pages.value[next] is PageState.Failed) return@launch
            }
        }
    }

    private val _rotations = MutableStateFlow(
        gallery.items.associate { it.key to scans.rotation(it) }.filterValues { it != 0 },
    )
    /** Clockwise quarter turns per item key; a page missing here is upright. */
    val rotations: StateFlow<Map<String, Int>> = _rotations.asStateFlow()

    private fun rotationOf(index: Int): Int = _rotations.value[gallery.items[index].key] ?: 0

    /**
     * Turns page [index] a quarter clockwise and reads it again: Lens gets the
     * turned pixels, and the old result (boxes for the old turn) is dropped
     * at once so it is never drawn over the turned picture.
     */
    fun rotate(index: Int) {
        val item = gallery.items[index]
        val turns = quarterTurns(rotationOf(index) + 1)
        scans.setRotation(item, turns)
        _rotations.update { if (turns == 0) it - item.key else it + (item.key to turns) }
        jobs.remove(index)?.cancel()
        _pages.update { it - index }
        _stale.update { it - index }
        _failed.update { it - index }
        _objects.update { it - index }
        ensure(index)
    }

    /** Tries page [index] again after a failure, or Lens again after a scan on the device. */
    fun retry(index: Int) {
        if (index !in gallery.items.indices) return
        jobs.remove(index)?.cancel()
        _pages.update { it - index }
        _failed.update { it - index }
        ensure(index)
        if (index == visible) preloadAround(index)
    }

    /** Asks the scanner for page [index] anew, whatever is remembered; the shown translation stays until the new one lands. */
    fun rescan(index: Int) {
        if (index !in gallery.items.indices) return
        if (_pages.value[index] is PageState.Ready) _stale.update { it + index } else _pages.update { it - index }
        _failed.update { it - index }
        ensure(index, fresh = true)
    }

    private val _searching = MutableStateFlow(false)
    /** A visual search is being prepared (upload, session, interaction). */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _searchError = MutableStateFlow<UiText?>(null)
    val searchError: StateFlow<UiText?> = _searchError.asStateFlow()

    private val _objects = MutableStateFlow<Map<Int, List<DetectedObject>>>(emptyMap())
    /** Objects Lens found per page, for the tappable dots in search mode. */
    val objects: StateFlow<Map<Int, List<DetectedObject>>> = _objects.asStateFlow()

    /** Uploads page [index] for search in the background, so its dots appear and a search starts fast. */
    fun prepareSearch(index: Int) {
        if (_objects.value.containsKey(index)) return
        val rotation = rotationOf(index)
        viewModelScope.launch {
            runCatching { scans.searchSession(gallery.items[index], settings.value, rotation) }
                .onSuccess { target -> if (rotationOf(index) == rotation) _objects.update { it + (index to target.objects) } }
        }
    }

    /**
     * Runs a visual search on page [index] and hands the results URL to
     * [open] (the user's browser). [build] picks what to search: the whole
     * image, a region, an object, or a question for AI Mode.
     */
    fun visualSearch(index: Int, open: (String) -> Unit, build: suspend (LensSearchSession) -> String) {
        if (_searching.value) return
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            try {
                val rotation = rotationOf(index)
                val target = try {
                    scans.searchSession(gallery.items[index], settings.value, rotation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Just back in front, the first request can fail on a connection the system
                    // cut while the app was away: one more try before saying so.
                    if (e is LensException) throw e
                    delay(SEARCH_RETRY_MS)
                    scans.searchSession(gallery.items[index], settings.value, rotation)
                }
                if (rotationOf(index) == rotation) _objects.update { it + (index to target.objects) }
                open(build(target.session))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchError.value = if (e is LensException) lensError(e) else friendlyError(e)
            } finally {
                _searching.value = false
            }
        }
    }

    fun dismissSearchError() { _searchError.value = null }

    fun searchTextUrl(text: String): String = scans.searchTextUrl(text)

    fun setTarget(code: String) = viewModelScope.launch { settingsRepository.update { it.copy(targetLanguage = code) } }

    fun setSource(code: String?) = viewModelScope.launch { settingsRepository.update { it.copy(sourceLanguage = code) } }

    fun setCompact(compact: Boolean) = viewModelScope.launch { settingsRepository.update { it.copy(viewerCompact = compact) } }

    /** A camera shot's mode, picked by hand, is what the next shot opens in. */
    fun rememberCameraMode(mode: ViewMode) {
        if (gallery.origin != GalleryOrigin.Camera) return
        viewModelScope.launch { settingsRepository.update { it.copy(cameraMode = mode) } }
    }

    private fun placeOf(index: Int): PagePlace? = gallery.folderKey?.let { PagePlace(it, gallery.title, index) }

    private fun ensure(index: Int, fresh: Boolean = false) {
        val state = _pages.value[index]
        val isStale = index in _stale.value
        if (!fresh && ((state is PageState.Ready && !isStale) || jobs[index]?.isActive == true)) return
        if (fresh) jobs.remove(index)?.cancel()
        // A stale page keeps its old result visible; only a page with nothing shows the spinner.
        if (!isStale) _pages.update { it + (index to PageState.Loading) }
        val rotation = rotationOf(index)
        val item = gallery.items[index]
        jobs[index] = viewModelScope.launch {
            val next = try {
                scans.scan(item, settings.value, rotation, fresh, placeOf(index)).let { PageState.Ready(it.result, it.offline) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnDeviceException) {
                PageState.Failed(onDeviceError(e))
            } catch (e: Exception) {
                // Lens failed and the on-device stand-in could not help for want of a language
                // model: that is the one thing the user can fix before the next time offline.
                val missing = e.suppressedExceptions.any { it is OnDeviceException && it.reason == OnDeviceException.Reason.ModelMissing }
                if (missing) PageState.Failed(uiText(Res.string.error_offline_no_model), FailKind.Offline) else scanError(e)
            }
            // A result read at a turn the page no longer has must not land.
            if (rotationOf(index) != rotation) return@launch
            _pages.update { it + (index to next) }
            _stale.update { it - index }
            _failed.update { if (next is PageState.Failed) it + index else it - index }
            if (next is PageState.Ready) _done.update { it + index }
        }
    }

    private fun scanError(e: Exception): PageState.Failed =
        when (e) {
            is LensException.Http ->
                if (e.status == 429) {
                    PageState.Failed(uiText(Res.string.error_lens_rate_limited), FailKind.RateLimited)
                } else {
                    PageState.Failed(uiText(Res.string.error_lens_http, e.status))
                }
            is LensException -> PageState.Failed(lensError(e))
            else -> PageState.Failed(friendlyError(e), if (isOffline(e)) FailKind.Offline else FailKind.Other)
        }

    private companion object {
        /** Two pages ahead: one is ready when the reader gets there, the other is on its way. */
        const val PRELOAD = 2

        /** Pages kept behind the reader, so paging back is instant without holding a whole chapter. */
        const val BEHIND = 2
    }
}

private const val SEARCH_RETRY_MS = 700L

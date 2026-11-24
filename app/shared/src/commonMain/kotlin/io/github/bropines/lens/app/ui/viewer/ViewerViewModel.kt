package io.github.bropines.lens.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Gallery
import io.github.bropines.lens.app.data.ScanRepository
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.uiText
import io.github.bropines.lens.protocol.DetectedObject
import io.github.bropines.lens.protocol.LensException
import io.github.bropines.lens.protocol.LensSearchSession
import io.github.bropines.lens.protocol.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A message a person can act on, instead of an exception's text. */
internal fun friendlyError(e: Throwable): UiText {
    val name = e::class.simpleName.orEmpty()
    return when {
        "Security" in name || "Permission" in name -> uiText(Res.string.error_no_file_access)
        "UnknownHost" in name || "Connect" in name || "Socket" in name -> uiText(Res.string.error_no_connection)
        "Timeout" in name -> uiText(Res.string.error_timeout)
        "FileNotFound" in name || "NoSuch" in name -> uiText(Res.string.error_file_not_found)
        else -> uiText(Res.string.error_generic, e.message ?: name)
    }
}

/** Lens's own message when it gave one. */
private fun lensError(e: LensException): UiText = e.message?.let(UiText::Plain) ?: uiText(Res.string.error_lens)

sealed interface PageState {
    data object Loading : PageState
    data class Ready(val result: ScanResult) : PageState
    data class Failed(val message: UiText) : PageState
}

class ViewerViewModel(
    val gallery: Gallery,
    private val scans: ScanRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _pages = MutableStateFlow<Map<Int, PageState>>(emptyMap())
    val pages: StateFlow<Map<Int, PageState>> = _pages.asStateFlow()

    private val jobs = mutableMapOf<Int, Job>()
    private var visible = gallery.startIndex

    private val _stale = MutableStateFlow<Set<Int>>(emptySet())
    /** Pages still showing the previous language's result while the new one is fetched. */
    val stale: StateFlow<Set<Int>> = _stale.asStateFlow()

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
                    onPageVisible(visible)
                }
        }
    }

    /** Scans the visible page and the next one, so a swipe usually lands on a finished page. */
    fun onPageVisible(index: Int) {
        visible = index
        ensure(index)
        if (index + 1 < gallery.items.size) ensure(index + 1)
    }

    fun retry(index: Int) {
        _pages.update { it - index }
        ensure(index)
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
        viewModelScope.launch {
            runCatching { scans.searchSession(gallery.items[index], settings.value) }
                .onSuccess { target -> _objects.update { it + (index to target.objects) } }
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
                val target = scans.searchSession(gallery.items[index], settings.value)
                _objects.update { it + (index to target.objects) }
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

    private fun ensure(index: Int) {
        val state = _pages.value[index]
        val isStale = index in _stale.value
        if ((state is PageState.Ready && !isStale) || jobs[index]?.isActive == true) return
        // A stale page keeps its old result visible; only a page with nothing shows the spinner.
        if (!isStale) _pages.update { it + (index to PageState.Loading) }
        jobs[index] = viewModelScope.launch {
            val next = try {
                PageState.Ready(scans.scan(gallery.items[index], settings.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LensException.Http) {
                PageState.Failed(if (e.status == 429) uiText(Res.string.error_lens_rate_limited) else uiText(Res.string.error_lens_http, e.status))
            } catch (e: LensException) {
                PageState.Failed(lensError(e))
            } catch (e: Exception) {
                PageState.Failed(friendlyError(e))
            }
            _pages.update { it + (index to next) }
            _stale.update { it - index }
        }
    }
}

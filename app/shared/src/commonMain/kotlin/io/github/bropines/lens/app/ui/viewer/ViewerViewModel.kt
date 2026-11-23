package io.github.bropines.lens.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.lens.app.data.AppSettings
import io.github.bropines.lens.app.data.Gallery
import io.github.bropines.lens.app.data.ScanRepository
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.protocol.LensException
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
internal fun friendlyError(e: Throwable): String {
    val name = e::class.simpleName.orEmpty()
    return when {
        "Security" in name || "Permission" in name -> "Нет доступа к файлу. Откройте его через «Поделиться» ещё раз."
        "UnknownHost" in name || "Connect" in name || "Socket" in name -> "Нет связи с Google. Проверьте интернет."
        "Timeout" in name -> "Google отвечает слишком долго. Повторите."
        "FileNotFound" in name || "NoSuch" in name -> "Файл не найден."
        else -> "Не получилось: ${e.message ?: name}"
    }
}

sealed interface PageState {
    data object Loading : PageState
    data class Ready(val result: ScanResult) : PageState
    data class Failed(val message: String) : PageState
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

    init {
        // A new language invalidates every page; rescan what the user is looking at.
        viewModelScope.launch {
            settingsRepository.settings
                .map { Triple(it.targetLanguage, it.sourceLanguage, it.ocrLanguage) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    _pages.value = emptyMap()
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

    fun setTarget(code: String) = viewModelScope.launch { settingsRepository.update { it.copy(targetLanguage = code) } }

    fun setSource(code: String?) = viewModelScope.launch { settingsRepository.update { it.copy(sourceLanguage = code) } }

    private fun ensure(index: Int) {
        val state = _pages.value[index]
        if (state is PageState.Ready || jobs[index]?.isActive == true) return
        _pages.update { it + (index to PageState.Loading) }
        jobs[index] = viewModelScope.launch {
            val next = try {
                PageState.Ready(scans.scan(gallery.items[index], settings.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LensException.Http) {
                PageState.Failed(if (e.status == 429) "Lens просит подождать (429)" else "Ошибка Lens: HTTP ${e.status}")
            } catch (e: LensException) {
                PageState.Failed(e.message ?: "Ошибка Lens")
            } catch (e: Exception) {
                PageState.Failed(friendlyError(e))
            }
            _pages.update { it + (index to next) }
        }
    }
}

package io.github.bropines.lens.app.ui.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.gtranslate.GoogleTranslateException
import io.github.bropines.lens.gtranslate.GoogleTranslation
import io.github.bropines.lens.gtranslate.GoogleTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TextTranslateState(
    val input: String = "",
    val source: String? = null,
    val target: String = "ru",
    val loading: Boolean = false,
    val result: GoogleTranslation? = null,
    val error: String? = null,
)

/** Translates as you type, after a short pause, and keeps only the latest request. */
@OptIn(FlowPreview::class)
class TextTranslateViewModel(
    private val translator: GoogleTranslator,
    private val settings: SettingsRepository,
    initial: String,
) : ViewModel() {
    private val _state = MutableStateFlow(TextTranslateState(input = initial))
    val state: StateFlow<TextTranslateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            _state.update { it.copy(target = s.targetLanguage, source = s.sourceLanguage) }
            _state
                .map { Triple(it.input, it.source, it.target) }
                .distinctUntilChanged()
                .debounce(450)
                .collectLatest { (input, source, target) -> translate(input, source, target) }
        }
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun setSource(code: String?) = _state.update { it.copy(source = code) }
    fun setTarget(code: String) = _state.update { it.copy(target = code) }

    private suspend fun translate(input: String, source: String?, target: String) {
        if (input.isBlank()) {
            _state.update { it.copy(result = null, error = null, loading = false) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            // A dictionary only for short input: Google returns one just for words and short phrases.
            val result = translator.translate(input, target, source ?: "auto", uiLanguage = "ru", dictionary = input.length <= 40)
            _state.update { it.copy(result = result, loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleTranslateException.RateLimited) {
            _state.update { it.copy(loading = false, error = "Google просит подождать (${e.retryAfter.inWholeSeconds} с)") }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Ошибка перевода") }
        }
    }
}

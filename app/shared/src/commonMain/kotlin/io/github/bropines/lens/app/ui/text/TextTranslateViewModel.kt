package io.github.bropines.lens.app.ui.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.TextTranslator
import io.github.bropines.lens.app.resources.*
import io.github.bropines.lens.app.ui.UiText
import io.github.bropines.lens.app.ui.uiText
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.PipelineResult
import io.github.bropines.lens.translate.TranslationException
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
    /** The engine picked for this text, or null for the user's order with fail-over. */
    val engine: String? = null,
    val loading: Boolean = false,
    val result: PipelineResult? = null,
    /** The language [result] is in, when the second language took over from [target]. */
    val swappedTo: String? = null,
    val error: UiText? = null,
)

/** Translates as you type, after a short pause, and keeps only the latest request. */
@OptIn(FlowPreview::class)
class TextTranslateViewModel(
    val translator: TextTranslator,
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
                .map { listOf(it.input, it.source, it.target, it.engine) }
                .distinctUntilChanged()
                .debounce(450)
                .collectLatest { translate() }
        }
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun setSource(code: String?) = _state.update { it.copy(source = code) }
    fun setTarget(code: String) = _state.update { it.copy(target = code) }
    fun setEngine(id: String?) = _state.update { it.copy(engine = id) }

    private suspend fun translate() {
        val s = _state.value
        if (s.input.isBlank()) {
            _state.update { it.copy(result = null, swappedTo = null, error = null, loading = false) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            // A dictionary only for short input: engines return one just for words and short phrases.
            val done = translator.translateTo(s.input, s.target, s.source, s.engine, dictionary = s.input.length <= 40)
            _state.update { it.copy(result = done.result, swappedTo = done.target.takeIf { t -> t != s.target }, loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            _state.update { it.copy(loading = false, error = describe(e)) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message?.let(UiText::Plain) ?: uiText(Res.string.error_translation)) }
        }
    }

    private fun describe(e: TranslationException): UiText = when (e.code) {
        ErrorCode.SameLanguage -> uiText(Res.string.error_same_language)
        ErrorCode.MissingCredential -> uiText(Res.string.error_missing_key, translator.nameOf(e.engineId.orEmpty()))
        ErrorCode.RateLimited -> uiText(Res.string.error_rate_limited)
        else -> uiText(Res.string.error_not_translated, e.message.orEmpty())
    }
}

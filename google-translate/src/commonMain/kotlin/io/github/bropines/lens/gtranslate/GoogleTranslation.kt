package io.github.bropines.lens.gtranslate

import kotlinx.serialization.Serializable

@Serializable
public data class GoogleTranslation(
    val text: String,
    /** The detected or given source language (Google's legacy codes: iw, zh-CN, tl, no). */
    val sourceLanguage: String?,
    val confidence: Float?,
    /** Romanisation of the source, e.g. for Japanese. For an English source this is phonetics; don't show it under the translation. */
    val sourceTransliteration: String?,
    /** Romanisation of the translation. */
    val transliteration: String?,
    val dictionary: List<DictionaryEntry>,
)

/** One part of speech with its alternatives, e.g. noun: [cat, kitty]. */
@Serializable
public data class DictionaryEntry(
    val partOfSpeech: String,
    val terms: List<String>,
    /** Each term with the words that translate back to it. */
    val backTranslations: List<Pair<String, List<String>>> = emptyList(),
)

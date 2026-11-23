package io.github.bropines.lens.app.data

import io.github.bropines.lens.gtranslate.GoogleTranslation

/** Scripts a reader of the app's languages may not read, where a romanisation helps. */
private val ROMANISE = setOf(
    "ja", "zh-CN", "zh-TW", "zh", "ko", "ar", "fa", "iw", "he", "hi", "th", "el", "ka", "hy", "bn", "ta", "te", "ur",
)

/** Romanisation of the source worth showing: Japanese romaji yes, English phonetics no. */
fun GoogleTranslation.usefulSourceRomanisation(): String? =
    sourceTransliteration?.takeIf { sourceLanguage in ROMANISE }

/** Romanisation of the translation, only when it is in a script like Japanese or Arabic. */
fun GoogleTranslation.usefulTargetRomanisation(target: String): String? =
    transliteration?.takeIf { target in ROMANISE }

package io.github.bropines.lens.app.data

import io.github.bropines.lens.translate.Translation

/** Scripts a reader of the app's languages may not read, where a romanisation helps. */
private val ROMANISE = setOf(
    "ja", "zh", "zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "ko", "ar", "fa", "iw", "he", "hi", "th", "el", "ka", "hy", "bn", "ta", "te", "ur",
)

private fun String?.romanised(): Boolean = this != null && (this in ROMANISE || substringBefore('-') in ROMANISE)

/** Romanisation of the source worth showing: Japanese romaji yes, English phonetics no. */
fun Translation.usefulSourceRomanisation(): String? =
    sourceTransliteration?.takeIf { it.isNotBlank() && detectedSource.romanised() }

/** Romanisation of the translation, only when it is in a script like Japanese or Arabic. */
fun Translation.usefulTargetRomanisation(target: String): String? =
    transliteration?.takeIf { it.isNotBlank() && target.romanised() }

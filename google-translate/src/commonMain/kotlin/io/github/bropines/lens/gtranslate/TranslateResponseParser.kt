package io.github.bropines.lens.gtranslate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Reads the positional JSON array of `/translate_a/single`. Every access is
 * safe: a slot that moved or vanished gives less detail, never an exception.
 */
internal object TranslateResponseParser {
    data class Parsed(
        val text: String,
        val sourceLanguage: String?,
        val confidence: Float?,
        val sourceTransliteration: String?,
        val transliteration: String?,
        val dictionary: List<DictionaryEntry>,
    )

    fun parse(body: String): Parsed {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw GoogleTranslateException.Unavailable("unreadable reply: ${body.take(120)}")
        }
        val segments = root[0] as? JsonArray ?: JsonArray(emptyList())
        // The transliteration segment is the one whose [0] is not a string.
        val (textSegments, romanSegments) = segments.partition { it[0].string() != null }
        val roman = romanSegments.lastOrNull()
        return Parsed(
            text = textSegments.joinToString("") { it[0].string().orEmpty() },
            sourceLanguage = root[2].string() ?: root[8][0][0].string(),
            confidence = root[6].float() ?: root[8][2][0].float(),
            transliteration = roman?.get(2)?.string()?.ifBlank { null },
            sourceTransliteration = roman?.get(3)?.string()?.ifBlank { null },
            dictionary = (root[1] as? JsonArray).orEmpty().mapNotNull(::dictionaryEntry),
        )
    }

    private fun dictionaryEntry(entry: JsonElement): DictionaryEntry? {
        val pos = entry[0].string() ?: return null
        val terms = (entry[1] as? JsonArray).orEmpty().mapNotNull { it.string() }
        val back = (entry[2] as? JsonArray).orEmpty().mapNotNull { item ->
            val word = item[0].string() ?: return@mapNotNull null
            word to (item[1] as? JsonArray).orEmpty().mapNotNull { it.string() }
        }
        return DictionaryEntry(pos, terms, back)
    }

    private operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.float(): Float? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
}

package io.github.bropines.lens.protocol

import kotlinx.serialization.Serializable

/**
 * What to ask Lens for. The default is plain OCR with no language stated,
 * which is the request Chromium sends when the user has chosen nothing.
 */
@Serializable
public data class ScanOptions(
    /**
     * `locale_context.language`. Empty means "not stated" and is not on the
     * wire at all. That is a different request from `"en"`.
     */
    val ocrLanguage: String = "",
    /**
     * Setting this is what turns the request into a translation: the TRANSLATE
     * filter *replaces* AUTO_FILTER. Without it Lens returns no translation.
     */
    val targetLanguage: String? = null,
    /** Left null, Lens detects the source language. */
    val sourceLanguage: String? = null,
    val region: String = LensDefaults.REGION,
    val timeZone: String = LensDefaults.TIME_ZONE,
    val alphaMode: AlphaMode = AlphaMode.Contrast,
)

/** How to flatten an image that really uses transparency before JPEG encoding. */
@Serializable
public enum class AlphaMode {
    /**
     * Onto white or black, whichever contrasts with the visible content. White
     * text on a transparent background would vanish on white.
     */
    Contrast,

    /** Always onto white. */
    White,
}

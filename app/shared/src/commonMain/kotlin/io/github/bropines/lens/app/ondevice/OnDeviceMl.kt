package io.github.bropines.lens.app.ondevice

import io.github.bropines.lens.protocol.ScanResult

/**
 * Text recognition and translation that run on the device, where the build
 * has them: the `google` flavor's ML Kit. The `foss` flavor and the desktop
 * have none, and everything built on this is hidden there.
 *
 * Languages are the app's internal BCP 47 tags (`zh-Hans`, `he`).
 */
interface OnDeviceMl {
    /** Languages that can be translated from and to, as internal tags. */
    val translationLanguages: List<String>

    /**
     * Reads the text in an encoded image with the recognizer for [script].
     * The result has paragraphs, lines and words with their boxes, and no
     * translation.
     */
    suspend fun recognize(image: ByteArray, script: OcrScript): ScanResult

    /** The language [text] is most likely in, or null when that cannot be told. */
    suspend fun identifyLanguage(text: String): String?

    /**
     * Translates each of [texts] from [source] to [target]. With [download] a
     * missing language model is fetched first (over Wi-Fi only if [wifiOnly]);
     * without it a missing model is an [OnDeviceException] at once, which is
     * what a fallback for "no network" needs.
     */
    suspend fun translate(texts: List<String>, source: String, target: String, download: Boolean, wifiOnly: Boolean): List<String>

    /** Every model this can use, with whether it is on the device. */
    suspend fun models(): List<OnDeviceModel>

    /** Fetches [model]. [onProgress] gets 0..1 where the platform reports it, else null. */
    suspend fun download(model: OnDeviceModel, wifiOnly: Boolean, onProgress: (Float?) -> Unit)

    /** Removes a downloaded [model] that is [OnDeviceModel.removable]. */
    suspend fun delete(model: OnDeviceModel)
}

/** The scripts there is a recognizer for. Latin is also what the others fall back to for Latin text. */
enum class OcrScript { Latin, Chinese, Japanese, Korean, Devanagari }

data class OnDeviceModel(
    val kind: Kind,
    /** A language tag for a translation model, an [OcrScript] name for a recognizer. */
    val id: String,
    val downloaded: Boolean,
    /** False for what the app cannot delete: built-in English, and recognizers that Google Play services keeps. */
    val removable: Boolean,
    /** Roughly how much a download is, when known. */
    val approxBytes: Long? = null,
) {
    enum class Kind { Translation, Recognizer }
}

class OnDeviceException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** A model the request needs is not on the device (and was not to be downloaded). */
        ModelMissing,

        /** The language pair or script is not one the device can do. */
        Unsupported,

        /** Anything else: the recognizer or translator failed. */
        Failed,
    }
}

/** The recognizer for text in [language] (a hint; null or an unknown one reads as Latin). */
fun ocrScriptFor(language: String?): OcrScript = when (language?.substringBefore('-')?.lowercase()) {
    "zh" -> OcrScript.Chinese
    "ja" -> OcrScript.Japanese
    "ko" -> OcrScript.Korean
    "hi", "mr", "ne", "sa" -> OcrScript.Devanagari
    else -> OcrScript.Latin
}

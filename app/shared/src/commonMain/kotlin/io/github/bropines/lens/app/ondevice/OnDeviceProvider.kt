package io.github.bropines.lens.app.ondevice

import io.github.bropines.lens.translate.Capability
import io.github.bropines.lens.translate.ErrorCode
import io.github.bropines.lens.translate.LanguageMap
import io.github.bropines.lens.translate.ProviderHost
import io.github.bropines.lens.translate.Translation
import io.github.bropines.lens.translate.TranslationException
import io.github.bropines.lens.translate.TranslationProvider
import io.github.bropines.lens.translate.TranslationRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The device's own translator as one of the text engines: nothing leaves the
 * phone, and it works with no network once a language's model is there.
 * Registered only where the build has [OnDeviceMl].
 */
class OnDeviceProvider(private val ml: OnDeviceMl, private val wifiOnly: () -> Boolean) : TranslationProvider {
    override val id: String = ID
    override val name: String = "On-device (ML Kit)"
    override val description: String = "Google ML Kit on the device: private and offline; languages are downloaded on first use."
    override val capabilities: Set<Capability> = setOf(Capability.AutoDetect)
    override val languages: LanguageMap = LanguageMap(supported = ml.translationLanguages)
    override val maxInputChars: Int = 5000

    /** The first use of a language downloads its model, some 30 MB. */
    override val requestTimeout: Duration = 3.minutes

    override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
        if (!languages.supportsTarget(request.target)) throw TranslationException(ErrorCode.TargetLanguage, "no on-device model for ${request.target}")
        val source = request.source
            ?: ml.identifyLanguage(request.text)
            ?: throw TranslationException(ErrorCode.SourceLanguage, "the language could not be identified on the device")
        if (!languages.supportsSource(source)) throw TranslationException(ErrorCode.SourceLanguage, "no on-device model for $source")
        val text = try {
            ml.translate(listOf(request.text), source, request.target, download = true, wifiOnly = wifiOnly()).single()
        } catch (e: OnDeviceException) {
            throw TranslationException(
                when (e.reason) {
                    // Another engine can still answer while the model waits for Wi-Fi.
                    OnDeviceException.Reason.ModelMissing -> ErrorCode.Network
                    OnDeviceException.Reason.Unsupported -> ErrorCode.Unsupported
                    OnDeviceException.Reason.Failed -> ErrorCode.BadResponse
                },
                e.message.orEmpty(),
                cause = e,
            )
        }
        return Translation(text, detectedSource = source.takeIf { request.source == null })
    }

    companion object {
        const val ID = "ondevice"
    }
}

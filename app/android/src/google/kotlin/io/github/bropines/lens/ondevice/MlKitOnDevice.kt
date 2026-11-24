package io.github.bropines.lens.ondevice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Point
import android.os.Build
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.bropines.lens.app.ondevice.OcrScript
import io.github.bropines.lens.app.ondevice.OnDeviceException
import io.github.bropines.lens.app.ondevice.OnDeviceException.Reason
import io.github.bropines.lens.app.ondevice.OnDeviceMl
import io.github.bropines.lens.app.ondevice.OnDeviceModel
import io.github.bropines.lens.protocol.Box
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.Word
import io.github.bropines.lens.protocol.WritingDirection
import io.github.bropines.lens.translate.Languages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes

/**
 * Google ML Kit behind [OnDeviceMl].
 *
 * Text recognition uses the recognizers whose models Google Play services
 * downloads and keeps (a few hundred kilobytes in the APK instead of several
 * megabytes per script); translation and its language models are ML Kit's
 * own, some 30 MB a language, fetched on demand through English as the pivot.
 */
class MlKitOnDevice(private val context: Context) : OnDeviceMl {
    /** ML Kit's code for each internal tag: it says `zh`, `tl` and `no` where the app says `zh-Hans`, `fil` and `nb`. */
    private val codes: Map<String, String> = TranslateLanguage.getAllLanguages().associateBy { Languages.normalize(it) }

    override val translationLanguages: List<String> = codes.keys.toList()

    private fun code(tag: String): String? {
        val normal = Languages.normalize(tag)
        // One Chinese model serves both scripts.
        return codes[normal] ?: codes[Languages.normalize(Languages.base(normal))]
    }

    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    private fun recognizer(script: OcrScript): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    OcrScript.Latin -> TextRecognizerOptions.DEFAULT_OPTIONS
                    OcrScript.Chinese -> ChineseTextRecognizerOptions.Builder().build()
                    OcrScript.Japanese -> JapaneseTextRecognizerOptions.Builder().build()
                    OcrScript.Korean -> KoreanTextRecognizerOptions.Builder().build()
                    OcrScript.Devanagari -> DevanagariTextRecognizerOptions.Builder().build()
                },
            )
        }
    }

    override suspend fun recognize(image: ByteArray, script: OcrScript): ScanResult = withContext(Dispatchers.Default) {
        val bitmap = decode(image)
        try {
            val text = guarded { recognizer(script).process(InputImage.fromBitmap(bitmap, 0)).await() }
            text.toScanResult(bitmap.width, bitmap.height, spaced = script != OcrScript.Chinese && script != OcrScript.Japanese)
        } finally {
            bitmap.recycle()
        }
    }

    /** Upright (EXIF applied) and no larger than [MAX_PIXELS]: boxes are fractions of the image, so scale does not show. */
    private fun decode(bytes: ByteArray): Bitmap = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("not an image")
        }
    } catch (e: Exception) {
        throw OnDeviceException(Reason.Failed, "cannot read the image: ${e.message}", e)
    }

    private fun sampleFor(width: Int, height: Int): Int = max(1, ceil(sqrt(width.toDouble() * height / MAX_PIXELS)).toInt())

    override suspend fun identifyLanguage(text: String): String? {
        if (text.isBlank()) return null
        // The identifier's model also comes from Play services; before it has arrived there is no answer.
        val code = runCatching { LanguageIdentification.getClient().use { it.identifyLanguage(text).await() } }.getOrNull()
        return code?.takeIf { it != "und" }?.let(Languages::normalize)
    }

    private val translatorLock = Mutex()

    /** The last pair's translator, kept open: loading a model takes far longer than a sentence. */
    private var translator: Pair<String, Translator>? = null

    override suspend fun translate(texts: List<String>, source: String, target: String, download: Boolean, wifiOnly: Boolean): List<String> {
        val from = code(source) ?: throw OnDeviceException(Reason.Unsupported, "no on-device model for $source")
        val to = code(target) ?: throw OnDeviceException(Reason.Unsupported, "no on-device model for $target")
        if (from == to) return texts
        return translatorLock.withLock {
            guarded {
                if (!download) {
                    for (language in setOf(from, to)) {
                        if (!models.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()) {
                            throw OnDeviceException(Reason.ModelMissing, "the $language model is not downloaded")
                        }
                    }
                }
                val client = translator?.takeIf { it.first == "$from>$to" }?.second ?: run {
                    translator?.second?.close()
                    Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
                        .also { translator = "$from>$to" to it }
                }
                if (download) {
                    try {
                        // Without the network it wants, ML Kit waits rather than fails; a person does not.
                        withTimeout(DOWNLOAD_WAIT) { client.downloadModelIfNeeded(conditions(wifiOnly)).await() }
                    } catch (e: TimeoutCancellationException) {
                        throw OnDeviceException(Reason.ModelMissing, "the language model is still downloading" + if (wifiOnly) " (waiting for Wi-Fi)" else "", e)
                    }
                }
                texts.map { if (it.isBlank()) it else client.translate(it).await() }
            }
        }
    }

    private val models get() = RemoteModelManager.getInstance()

    private fun conditions(wifiOnly: Boolean) = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()

    override suspend fun models(): List<OnDeviceModel> {
        val modules = ModuleInstall.getClient(context)
        val recognizers = OcrScript.entries.map { script ->
            val there = runCatching { modules.areModulesAvailable(recognizer(script)).await().areModulesAvailable() }.getOrDefault(false)
            OnDeviceModel(OnDeviceModel.Kind.Recognizer, script.name, downloaded = there, removable = false)
        }
        val downloaded = runCatching { models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() }
            .getOrDefault(emptySet())
        val languages = codes.map { (tag, code) ->
            OnDeviceModel(
                OnDeviceModel.Kind.Translation, tag,
                downloaded = code in downloaded,
                // English is the pivot every pair goes through; ML Kit keeps it.
                removable = code != TranslateLanguage.ENGLISH,
                approxBytes = MODEL_BYTES,
            )
        }
        return recognizers + languages
    }

    override suspend fun download(model: OnDeviceModel, wifiOnly: Boolean, onProgress: (Float?) -> Unit) {
        when (model.kind) {
            OnDeviceModel.Kind.Translation -> {
                val code = code(model.id) ?: throw OnDeviceException(Reason.Unsupported, "no on-device model for ${model.id}")
                // ML Kit reports no progress for a language model, only the end.
                onProgress(null)
                // ML Kit hands the file to the system's DownloadManager, which can park it
                // indefinitely (a VPN or ad blocker in the way, a vendor ROM holding background
                // jobs) without ever failing. Give up and say so rather than spin forever.
                try {
                    withTimeout(MODEL_DOWNLOAD_LIMIT) {
                        guarded { models.download(TranslateRemoteModel.Builder(code).build(), conditions(wifiOnly)).await() }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw OnDeviceException(Reason.Failed, "the system download manager did not finish the model" + if (wifiOnly) " (it waits for Wi-Fi)" else "", e)
                }
            }
            OnDeviceModel.Kind.Recognizer -> installRecognizer(OcrScript.valueOf(model.id), onProgress)
        }
    }

    /** Asks Google Play services for a recognizer's module now, instead of on the first scan that needs it. */
    private suspend fun installRecognizer(script: OcrScript, onProgress: (Float?) -> Unit) {
        val modules = ModuleInstall.getClient(context)
        val finished = CompletableDeferred<Unit>()
        val listener = InstallStatusListener { update ->
            update.progressInfo?.takeIf { it.totalBytesToDownload > 0 }?.let { onProgress(it.bytesDownloaded.toFloat() / it.totalBytesToDownload) }
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finished.complete(Unit)
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED, ModuleInstallStatusUpdate.InstallState.STATE_CANCELED ->
                    finished.completeExceptionally(OnDeviceException(Reason.Failed, "Google Play services could not install it (error ${update.errorCode})"))
            }
        }
        onProgress(null)
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(recognizer(script)).setListener(listener).build()
            val response = guarded { modules.installModules(request).await() }
            if (!response.areModulesAlreadyInstalled()) finished.await()
        } finally {
            modules.unregisterListener(listener)
        }
    }

    override suspend fun delete(model: OnDeviceModel) {
        if (model.kind != OnDeviceModel.Kind.Translation || !model.removable) return
        val code = code(model.id) ?: return
        translatorLock.withLock {
            // A translator holding the model open would keep using a file that is gone.
            translator?.second?.close()
            translator = null
            guarded { models.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await() }
        }
    }

    /** Runs an ML Kit call, turning its failures into [OnDeviceException]. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OnDeviceException) {
        throw e
    } catch (e: MlKitException) {
        // UNAVAILABLE is "the model is not here yet": Play services is still fetching a recognizer.
        val reason = if (e.errorCode == MlKitException.UNAVAILABLE || e.errorCode == MlKitException.NOT_FOUND) Reason.ModelMissing else Reason.Failed
        throw OnDeviceException(reason, e.message ?: "ML Kit error ${e.errorCode}", e)
    } catch (e: Exception) {
        throw OnDeviceException(Reason.Failed, e.message ?: e.javaClass.simpleName, e)
    }

    private companion object {
        /** Plenty for text, and a bound on what one decoded page can cost in memory. */
        const val MAX_PIXELS = 16_000_000.0

        /** What Google's documentation gives for a language model. */
        const val MODEL_BYTES = 30_000_000L
        val DOWNLOAD_WAIT = 2.minutes
        val MODEL_DOWNLOAD_LIMIT = 4.minutes
    }
}

/** A block is a paragraph, a line a line, an element a word; none of it carries a translation yet. */
private fun Text.toScanResult(width: Int, height: Int, spaced: Boolean): ScanResult {
    val separator = if (spaced) " " else ""
    val paragraphs = textBlocks.map { block ->
        val lines = block.lines.map { line ->
            Line(
                text = line.text,
                words = line.elements.mapIndexed { i, element ->
                    Word(element.text, if (i < line.elements.lastIndex) separator else "", box(element.cornerPoints, width, height))
                },
                box = box(line.cornerPoints, width, height),
                translation = null,
                translationStyle = null,
            )
        }
        Paragraph(
            // ML Kit joins a block's lines with newlines; a translator needs the sentence in one piece.
            text = lines.joinToString(separator) { it.text },
            lines = lines,
            box = box(block.cornerPoints, width, height),
            language = block.recognizedLanguage.takeIf { it.isNotEmpty() && it != "und" }?.let(Languages::normalize),
            writingDirection = WritingDirection.LeftToRight,
            translation = null,
        )
    }
    return ScanResult(paragraphs, language = null, imageWidth = width, imageHeight = height)
}

/**
 * ML Kit's four corners (clockwise from the top left, in pixels) as the
 * rotated, normalised box the overlay draws with.
 */
private fun box(corners: Array<Point>?, width: Int, height: Int): Box? {
    if (corners == null || corners.size < 4) return null
    val (topLeft, topRight, bottomRight) = corners
    return Box(
        centerX = corners.sumOf { it.x }.toFloat() / corners.size / width,
        centerY = corners.sumOf { it.y }.toFloat() / corners.size / height,
        width = hypot((topRight.x - topLeft.x).toFloat(), (topRight.y - topLeft.y).toFloat()) / width,
        height = hypot((bottomRight.x - topRight.x).toFloat(), (bottomRight.y - topRight.y).toFloat()) / height,
        // Screen coordinates grow downwards, so this angle is already clockwise.
        rotation = atan2((topRight.y - topLeft.y).toFloat(), (topRight.x - topLeft.x).toFloat()),
    )
}

/** A Play services [Task] as a suspending call. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

package io.github.bropines.lens.translate

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The user's choice of engines. */
public data class TranslatorSettings(
    /** Ids of the enabled engines in the user's order. No other engine ever sees the text. */
    val enabled: List<String>,
    /** Option values by engine id, then by [OptionSpec.key]. */
    val options: Map<String, Map<String, String>> = emptyMap(),
    /** Try the next enabled engine when one fails. */
    val failOver: Boolean = true,
)

public data class PipelineResult(
    val translation: Translation,
    /** The engine that produced [translation]. */
    val engineId: String,
    /** Engines tried or skipped before it, in order, and why. */
    val failures: List<EngineFailure> = emptyList(),
)

/**
 * Translates with the user's engines in their order, moving to the next one
 * when an engine fails.
 *
 * - Candidates are the requested engine, then (with fail-over on) the other
 *   enabled engines. An engine that is not enabled is never sent the text,
 *   not even for language detection.
 * - An engine that does not know the target (or the given source) is skipped.
 *   One that cannot detect the source gets a language detected by another
 *   enabled engine.
 * - Fail-over stops at errors another engine would not fix: a missing or
 *   rejected credential, empty input, identical languages, cancellation
 *   ([ErrorCode.stopsFailOver]). An empty translation counts as a failure.
 * - Long text is cut by the engine's [TranslationProvider.maxInputChars]; the
 *   whitespace between chunks is restored and the source language is fixed
 *   after the first chunk.
 * - A connection error, a 429 or a 500/502/503/504 is retried [retries] times
 *   after about 0.6 s (doubling, 50-100 % jitter); a Retry-After of up to 5 s
 *   is honoured and a longer one ends the retries. A timeout is not retried.
 *
 * @param store where providers keep their state; each one gets its own namespace.
 */
public class TranslationPipeline(
    private val registry: TranslatorRegistry,
    private val http: HttpClient,
    private val settings: () -> TranslatorSettings,
    private val store: KeyValueStore = MemoryKeyValueStore(),
    private val uiLanguage: () -> String = { "en" },
    private val retries: Int = 1,
    /** An engine that would need more requests than this for one text is passed over. */
    private val maxChunks: Int = 20,
    private val clock: () -> Long = ::systemMillis,
) {
    private val log = Logger.withTag("TranslationPipeline")

    /**
     * @param source a language tag, or null (or `auto`) to detect.
     * @param engine the engine to try first; it must be enabled. Null means the first enabled one.
     * @throws TranslationException with [TranslationException.failures] when no engine produced a translation.
     */
    public suspend fun translate(
        text: String,
        target: String,
        source: String? = null,
        engine: String? = null,
        dictionary: Boolean = false,
    ): PipelineResult {
        val current = settings()
        if (text.isBlank()) throw TranslationException(ErrorCode.EmptyInput, "nothing to translate")
        val to = Languages.normalize(target)
        val from = source?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }?.let(Languages::normalize)
        if (from == to) throw TranslationException(ErrorCode.SameLanguage, "source and target are both $to")

        val candidates = candidates(current, engine)
        val failures = mutableListOf<EngineFailure>()
        val run = Run(current, candidates, text, to, from, dictionary)
        for (id in candidates) {
            val failure = try {
                val provider = registry[id] ?: throw Skip(ErrorCode.NotFound, "no engine with id $id")
                return PipelineResult(run.with(provider), id, failures.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Skip) {
                EngineFailure(id, e.code, e.message.orEmpty(), skipped = true)
            } catch (e: TranslationException) {
                EngineFailure(id, e.code, e.message.orEmpty()).also {
                    if (e.code.stopsFailOver) throw e.withFailures(id, failures + it)
                }
            } catch (e: Exception) {
                // A provider bug must not take the application down, nor stop the fail-over.
                EngineFailure(id, ErrorCode.BadResponse, "the service changed its response (${e::class.simpleName}: ${e.message})")
            }
            log.w { "$id: ${failure.code.id}: ${failure.message}" }
            failures += failure
        }
        val last = failures.lastOrNull { !it.skipped } ?: failures.last()
        throw TranslationException(
            last.code,
            failures.joinToString("; ") { "${it.engineId}: ${it.message}" },
            engineId = last.engineId,
            failures = failures.toList(),
        )
    }

    private fun candidates(current: TranslatorSettings, engine: String?): List<String> {
        val enabled = current.enabled.distinct()
        if (enabled.isEmpty()) throw TranslationException(ErrorCode.BadRequest, "no translation engine is enabled")
        if (engine == null) return if (current.failOver) enabled else enabled.take(1)
        if (engine !in enabled) throw TranslationException(ErrorCode.BadRequest, "engine $engine is not enabled", engineId = engine)
        return if (current.failOver) listOf(engine) + (enabled - engine) else listOf(engine)
    }

    private fun hostFor(provider: TranslationProvider, current: TranslatorSettings): ProviderHost = SimpleProviderHost(
        http = http,
        options = current.options[provider.id].orEmpty(),
        uiLanguage = uiLanguage(),
        store = Namespaced(store, provider.id + "/"),
        clock = clock,
    )

    /** One [translate] call: remembers the language detected for engines that cannot detect. */
    private inner class Run(
        val current: TranslatorSettings,
        val candidates: List<String>,
        val text: String,
        val target: String,
        val givenSource: String?,
        val dictionary: Boolean,
    ) {
        private var detected: String? = null
        private var detectionTried = false

        suspend fun with(provider: TranslationProvider): Translation {
            if (!provider.languages.supportsTarget(target)) {
                throw Skip(ErrorCode.TargetLanguage, "${provider.name} does not translate into $target")
            }
            val host = hostFor(provider, current)
            provider.missingOption(host)?.let {
                throw TranslationException(ErrorCode.MissingCredential, "${provider.name}: \"${it.label}\" is not set")
            }
            var source = givenSource
            if (source == null && Capability.AutoDetect !in provider.capabilities) {
                source = detect() ?: throw Skip(ErrorCode.SourceLanguage, "${provider.name} needs the source language and no enabled engine detected it")
                if (source == target) throw TranslationException(ErrorCode.SameLanguage, "the text is already in $target")
            }
            if (source != null && !provider.languages.supportsSource(source)) {
                throw Skip(ErrorCode.SourceLanguage, "${provider.name} does not translate from $source")
            }

            val chunks = TextChunker.split(text, provider.maxInputChars)
            if (chunks.size > maxChunks) {
                throw Skip(ErrorCode.BadRequest, "the text needs ${chunks.size} requests to ${provider.name}, over the limit of $maxChunks")
            }
            var first: Translation? = null
            val out = StringBuilder()
            val romanised = mutableListOf<String>()
            val sourceRomanised = mutableListOf<String>()
            for (chunk in chunks) {
                out.append(chunk.leading)
                if (chunk.body.isNotEmpty()) {
                    // A dictionary only makes sense for a text that is one short chunk.
                    val request = TranslationRequest(chunk.body, target, source, dictionary && chunks.size == 1)
                    val part = attempt(provider) { provider.translate(request, host) }
                    if (part.text.isBlank()) throw TranslationException(ErrorCode.BadResponse, "${provider.name} returned an empty translation")
                    if (first == null) {
                        first = part
                        // Fixed after the first chunk, or a later chunk might be detected as something else.
                        if (source == null) source = part.detectedSource?.takeIf { provider.languages.supportsSource(it) }
                    }
                    out.append(part.text.trim())
                    part.transliteration?.let { romanised += it }
                    part.sourceTransliteration?.let { sourceRomanised += it }
                }
                out.append(chunk.trailing)
            }
            val head = first ?: throw TranslationException(ErrorCode.EmptyInput, "nothing to translate")
            return head.copy(
                text = out.toString(),
                detectedSource = head.detectedSource ?: source,
                transliteration = romanised.joinToString(" ").ifEmpty { null },
                sourceTransliteration = sourceRomanised.joinToString(" ").ifEmpty { null },
            )
        }

        /** Asks the enabled engines that can detect, in the user's order, once per run. */
        private suspend fun detect(): String? {
            if (detectionTried) return detected
            detectionTried = true
            for (id in candidates) {
                val provider = registry[id]?.takeIf { Capability.Detect in it.capabilities } ?: continue
                val host = hostFor(provider, current)
                if (provider.missingOption(host) != null) continue
                try {
                    val sample = text.trim().take(minOf(500, provider.maxInputChars))
                    detected = withTimeoutOrNull(provider.requestTimeout) { provider.detect(sample, host) }?.language
                    if (detected != null) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w { "$id could not detect the language: ${e.message}" }
                }
            }
            return detected
        }
    }

    private suspend fun <T : Any> attempt(provider: TranslationProvider, block: suspend () -> T): T {
        var pause = 600.milliseconds
        for (round in 0..retries) {
            try {
                return withTimeoutOrNull(provider.requestTimeout) { block() }
                    ?: throw TranslationException(ErrorCode.Timeout, "${provider.name} did not answer in ${provider.requestTimeout}")
            } catch (e: TranslationException) {
                if (round == retries || !e.retryable()) throw e
                val jittered = pause * (0.5 + Random.nextDouble() * 0.5)
                delay(maxOf(jittered, e.retryAfter ?: Duration.ZERO))
                pause *= 2
            }
        }
        error("unreachable")
    }

    private fun TranslationException.retryable(): Boolean = when (code) {
        ErrorCode.Network -> true
        ErrorCode.RateLimited -> (httpStatus == null || httpStatus == 429) && (retryAfter ?: Duration.ZERO) <= MAX_RETRY_AFTER
        ErrorCode.Http -> httpStatus in RETRY_STATUSES
        else -> false
    }

    private fun TranslationException.withFailures(id: String, all: List<EngineFailure>) =
        TranslationException(code, message.orEmpty(), retryAfter, httpStatus, id, all, this)

    /** An engine passed over without being sent the text. */
    private class Skip(val code: ErrorCode, message: String) : Exception(message)

    private class Namespaced(private val store: KeyValueStore, private val prefix: String) : KeyValueStore {
        override suspend fun get(key: String): String? = store.get(prefix + key)
        override suspend fun set(key: String, value: String?) = store.set(prefix + key, value)
    }

    private companion object {
        val MAX_RETRY_AFTER = 5.seconds
        val RETRY_STATUSES = setOf(500, 502, 503, 504)
    }
}

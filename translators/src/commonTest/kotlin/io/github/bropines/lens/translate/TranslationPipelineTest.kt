package io.github.bropines.lens.translate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class TranslationPipelineTest {
    private class Fake(
        override val id: String,
        override val capabilities: Set<Capability> = setOf(Capability.AutoDetect),
        override val languages: LanguageMap = LanguageMap(),
        override val maxInputChars: Int = 1000,
        override val options: List<OptionSpec> = emptyList(),
        private val detects: String? = null,
        private val reply: suspend (TranslationRequest) -> Translation = { Translation("[$id] ${it.text}", detectedSource = "en") },
    ) : TranslationProvider {
        val seen = mutableListOf<TranslationRequest>()
        val detectCalls = mutableListOf<String>()
        override val name: String get() = id

        override suspend fun translate(request: TranslationRequest, host: ProviderHost): Translation {
            seen += request
            return reply(request)
        }

        override suspend fun detect(text: String, host: ProviderHost): Detection? {
            detectCalls += text
            return detects?.let { Detection(it) }
        }
    }

    private fun failing(id: String, error: () -> TranslationException) = Fake(id, reply = { throw error() })

    private fun pipeline(vararg providers: TranslationProvider, enabled: List<String> = providers.map { it.id }, failOver: Boolean = true) =
        TranslationPipeline(
            registry = TranslatorRegistry(providers.toList()),
            http = HttpClient(MockEngine { error("the pipeline itself makes no requests") }),
            settings = { TranslatorSettings(enabled, failOver = failOver) },
        )

    @Test
    fun failsOverToTheNextEngineAndSaysWhoFailedAndWhy() = runTest {
        val a = failing("a") { TranslationException(ErrorCode.Http, "HTTP 500", httpStatus = 500) }
        val b = failing("b") { TranslationException(ErrorCode.BadResponse, "the service changed its response") }
        val c = Fake("c")
        val result = pipeline(a, b, c).translate("hello", "ru")
        result.engineId shouldBe "c"
        result.translation.text shouldBe "[c] hello"
        result.failures.map { it.engineId to it.code } shouldBe listOf("a" to ErrorCode.Http, "b" to ErrorCode.BadResponse)
        // A 500 is retried once; a changed response is not.
        a.seen.size shouldBe 2
        b.seen.size shouldBe 1
    }

    @Test
    fun anEngineTheUserDidNotEnableNeverSeesTheText() = runTest {
        val a = failing("a") { TranslationException(ErrorCode.BadResponse, "broken") }
        val b = Fake("b", capabilities = setOf(Capability.AutoDetect, Capability.Detect), detects = "en")
        val noDetect = Fake("n", capabilities = emptySet())
        val error = shouldThrow<TranslationException> { pipeline(a, b, noDetect, enabled = listOf("n", "a")).translate("hello", "ru") }
        error.failures.map { it.engineId } shouldBe listOf("n", "a")
        error.code shouldBe ErrorCode.BadResponse
        b.seen shouldBe emptyList()
        // Not even to detect the language for the engine that cannot.
        b.detectCalls shouldBe emptyList()
        noDetect.seen shouldBe emptyList()
    }

    @Test
    fun withFailOverOffOnlyTheFirstEngineIsTried() = runTest {
        val a = failing("a") { TranslationException(ErrorCode.BadResponse, "broken") }
        val b = Fake("b")
        shouldThrow<TranslationException> { pipeline(a, b, failOver = false).translate("hello", "ru") }
        b.seen shouldBe emptyList()
    }

    @Test
    fun anEngineThatDoesNotKnowTheTargetIsSkipped() = runTest {
        val a = Fake("a", languages = LanguageMap(supported = setOf("en", "ru")))
        val b = Fake("b")
        val result = pipeline(a, b).translate("hello", "ja")
        result.engineId shouldBe "b"
        a.seen shouldBe emptyList()
        result.failures.single() shouldBe EngineFailure("a", ErrorCode.TargetLanguage, "a does not translate into ja", skipped = true)
    }

    @Test
    fun aSettingsErrorStopsTheFailOver() = runTest {
        val a = Fake("a", options = listOf(OptionSpec("api_key", OptionType.Secret, "API key", required = true)))
        val b = Fake("b")
        val error = shouldThrow<TranslationException> { pipeline(a, b).translate("hello", "ru") }
        error.code shouldBe ErrorCode.MissingCredential
        error.engineId shouldBe "a"
        a.seen shouldBe emptyList()
        b.seen shouldBe emptyList()
    }

    @Test
    fun emptyInputAndIdenticalLanguagesAreNotSentAnywhere() = runTest {
        val a = Fake("a")
        shouldThrow<TranslationException> { pipeline(a).translate("  \n", "ru") }.code shouldBe ErrorCode.EmptyInput
        shouldThrow<TranslationException> { pipeline(a).translate("hello", "zh-CN", source = "zh") }.code shouldBe ErrorCode.SameLanguage
        a.seen shouldBe emptyList()
    }

    @Test
    fun anEmptyTranslationCountsAsAFailure() = runTest {
        val a = Fake("a", reply = { Translation("  ") })
        val b = Fake("b")
        val result = pipeline(a, b).translate("hello", "ru")
        result.engineId shouldBe "b"
        result.failures.single().code shouldBe ErrorCode.BadResponse
    }

    @Test
    fun longTextIsChunkedByTheEnginesLimitAndTheWhitespaceComesBack() = runTest {
        val text = "  One two three. Four five six.\n\nSeven eight nine. Ten.\n"
        var calls = 0
        val a = Fake("a", maxInputChars = 20, reply = {
            // Engines trim; only the first chunk is asked to detect.
            Translation(" ${it.text.uppercase()} ", detectedSource = if (calls++ == 0) "en" else "fr", transliteration = "r$calls")
        })
        val result = pipeline(a).translate(text, "ru", dictionary = true)
        result.translation.text shouldBe text.uppercase()
        a.seen.size shouldBe 4
        a.seen.all { it.text.length <= 20 } shouldBe true
        a.seen.first().source shouldBe null
        // The source language is fixed after the first chunk.
        a.seen.drop(1).map { it.source }.distinct() shouldBe listOf("en")
        // A dictionary is only asked for a text that fits one chunk.
        a.seen.any { it.dictionary } shouldBe false
        result.translation.detectedSource shouldBe "en"
        result.translation.transliteration shouldBe "r1 r2 r3 r4"
    }

    @Test
    fun aTextThatNeedsTooManyRequestsGoesToAnEngineWithALargerLimit() = runTest {
        val small = Fake("small", maxInputChars = 5)
        val big = Fake("big", maxInputChars = 10_000)
        val result = pipeline(small, big).translate("word ".repeat(200), "ru")
        result.engineId shouldBe "big"
        small.seen shouldBe emptyList()
        result.failures.single().skipped shouldBe true
    }

    @Test
    fun anEngineThatCannotDetectGetsTheLanguageFromAnEnabledOneThatCan() = runTest {
        val needsSource = Fake("mm", capabilities = emptySet())
        val detector = Fake("g", capabilities = setOf(Capability.AutoDetect, Capability.Detect), detects = "fr")
        val result = pipeline(needsSource, detector).translate("bonjour", "ru")
        result.engineId shouldBe "mm"
        needsSource.seen.single().source shouldBe "fr"
        detector.seen shouldBe emptyList()
        detector.detectCalls shouldBe listOf("bonjour")
    }

    @Test
    fun aTimeoutIsNotRetriedAndTheNextEngineIsTried() = runTest {
        val slow = Fake("slow", reply = {
            delay(60.seconds)
            Translation("late")
        })
        val b = Fake("b")
        val result = pipeline(slow, b).translate("hello", "ru")
        result.engineId shouldBe "b"
        result.failures.single().code shouldBe ErrorCode.Timeout
        slow.seen.size shouldBe 1
    }

    @Test
    fun aLongRetryAfterIsNotWaitedForButAShortOneIs() = runTest {
        val long = failing("long") { TranslationException(ErrorCode.RateLimited, "429", retryAfter = 30.seconds, httpStatus = 429) }
        var calls = 0
        val short = Fake("short", reply = {
            if (calls++ == 0) throw TranslationException(ErrorCode.RateLimited, "429", retryAfter = 2.seconds, httpStatus = 429)
            Translation("ok")
        })
        val result = pipeline(long, short).translate("hello", "ru")
        result.engineId shouldBe "short"
        long.seen.size shouldBe 1
        short.seen.size shouldBe 2
        result.failures.single().code shouldBe ErrorCode.RateLimited
    }

    @Test
    fun theRequestedEngineGoesFirstAndMustBeEnabled() = runTest {
        val a = Fake("a")
        val b = Fake("b")
        val c = Fake("c")
        pipeline(a, b, c, enabled = listOf("a", "b")).translate("hello", "ru", engine = "b").engineId shouldBe "b"
        a.seen shouldBe emptyList()
        val error = shouldThrow<TranslationException> { pipeline(a, b, c, enabled = listOf("a", "b")).translate("hello", "ru", engine = "c") }
        error.code shouldBe ErrorCode.BadRequest
        c.seen shouldBe emptyList()
    }

    @Test
    fun aCrashInAProviderIsAFailureNotACrash() = runTest {
        val a = Fake("a", reply = { throw IllegalStateException("bug") })
        val b = Fake("b")
        val result = pipeline(a, b).translate("hello", "ru")
        result.engineId shouldBe "b"
        result.failures.single().code shouldBe ErrorCode.BadResponse
    }

    @Test
    fun chunkerPrefersNewlinesThenSentencesAndKeepsEveryCharacter() {
        val text = "a".repeat(7000) + "\n" + "b".repeat(7000)
        val chunks = TextChunker.split(text, 10_000)
        chunks.size shouldBe 2
        chunks[0].body shouldBe "a".repeat(7000)
        chunks[0].trailing shouldBe "\n"
        chunks.joinToString("") { it.leading + it.body + it.trailing } shouldBe text

        TextChunker.split("First one. Second one. Third", 14).map { it.body } shouldBe listOf("First one.", "Second one.", "Third")
        TextChunker.split("你好。再见。", 4).map { it.body } shouldBe listOf("你好。", "再见。")
    }
}

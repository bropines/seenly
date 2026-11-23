package io.github.bropines.lens.translate

import io.github.bropines.lens.translate.providers.GoogleProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GoogleProviderTest {
    // Shape of a real reply with dt=t,rm,ld,bd for "猫".
    private val sample = """
        [[["Cat","猫",null,null,10],[null,null,"Kot","Neko"]],
         [["noun",["cat","kitty"],[["cat",["猫","ネコ"]]],"猫",1]],
         "ja",null,null,null,0.98,[],[["ja"],null,[0.98],["ja"]]]
    """.trimIndent()

    private val captcha = "<html>Our systems have detected unusual traffic</html>"

    @Test
    fun parsesTextLanguageTransliterationAndDictionary() {
        val parsed = GoogleProvider.parse(sample)
        parsed.text shouldBe "Cat"
        parsed.sourceLanguage shouldBe "ja"
        parsed.confidence shouldBe 0.98f
        parsed.transliteration shouldBe "Kot"
        parsed.sourceTransliteration shouldBe "Neko"
        val entry = parsed.dictionary.single()
        entry.partOfSpeech shouldBe "noun"
        entry.terms.map { it.text } shouldBe listOf("cat", "kitty")
        entry.terms.first().meanings shouldBe listOf("猫", "ネコ")
    }

    @Test
    fun missingSlotsGiveLessDetailNotACrash() {
        val parsed = GoogleProvider.parse("""[[["Hi","Привет"]]]""")
        parsed.text shouldBe "Hi"
        parsed.sourceLanguage shouldBe null
        parsed.dictionary shouldBe emptyList()
        shouldThrow<TranslationException> { GoogleProvider.parse("<html>") }.code shouldBe ErrorCode.BadResponse
    }

    @Test
    fun translatesWithLegacyCodesAndMapsTheDetectedLanguageBack() = runTest {
        val queries = mutableListOf<Map<String, List<String>>>()
        val host = host { request ->
            queries += request.url.parameters.entries().associate { it.key to it.value }
            json("""[[["שלום","hello"]],null,"zh-CN"]""")
        }
        val result = GoogleProvider.translate(TranslationRequest("hello", "he", dictionary = true), host)
        result.text shouldBe "שלום"
        result.detectedSource shouldBe "zh-Hans"
        queries.single()["tl"] shouldBe listOf("iw")
        queries.single()["sl"] shouldBe listOf("auto")
        queries.single()["client"] shouldBe listOf("dict-chrome-ex")
        queries.single()["dt"] shouldBe listOf("t", "rm", "ld", "bd", "md", "ex", "ss")
        queries.single()["q"] shouldBe listOf("hello")
    }

    @Test
    fun aCaptchaOnTheFirstHostFallsThroughToTheNextAndBenchesIt() = runTest {
        val hosts = mutableListOf<String>()
        var now = 0L
        val host = host(now = { now }) { request ->
            hosts += request.url.host
            if (request.url.host == "clients5.google.com") {
                respond(captcha, HttpStatusCode.TooManyRequests, HTML)
            } else {
                json(sample)
            }
        }
        val request = TranslationRequest("猫", "en")
        GoogleProvider.translate(request, host).text shouldBe "Cat"
        // The benched host is skipped on the next call...
        GoogleProvider.translate(request, host)
        hosts shouldBe listOf("clients5.google.com", "translate.googleapis.com", "translate.googleapis.com")
        // ...and tried again when the bench is over.
        now += 31_000
        GoogleProvider.translate(request, host)
        hosts.takeLast(2) shouldBe listOf("clients5.google.com", "translate.googleapis.com")
    }

    @Test
    fun jsonThatMentionsUnusualTrafficIsNotACaptcha() {
        GoogleProvider.classify(200, "application/json", """[[["unusual traffic /sorry/","x"]]]""") shouldBe GoogleProvider.Verdict.Ok
        GoogleProvider.classify(200, "text/html", "<html>/sorry/index</html>") shouldBe GoogleProvider.Verdict.Captcha
        GoogleProvider.classify(200, "text/html", "<html>consent</html>") shouldBe GoogleProvider.Verdict.TryNext
        GoogleProvider.classify(400, "application/json", "{}") shouldBe GoogleProvider.Verdict.Fail
    }

    @Test
    fun allHostsBenchedIsRateLimitedWithTheShortestWaitAndSendsNothingMore() = runTest {
        var requests = 0
        val host = host(now = { 0L }) {
            requests++
            respond(captcha, HttpStatusCode.TooManyRequests, headersOf("Content-Type" to listOf("text/html"), "Retry-After" to listOf("120")))
        }
        val first = shouldThrow<TranslationException> { GoogleProvider.translate(TranslationRequest("a", "en"), host) }
        first.code shouldBe ErrorCode.RateLimited
        first.retryAfter shouldBe 120.seconds
        requests shouldBe 3
        shouldThrow<TranslationException> { GoogleProvider.translate(TranslationRequest("a", "en"), host) }.code shouldBe ErrorCode.RateLimited
        requests shouldBe 3
    }

    @Test
    fun theBenchDoublesUpToTenMinutes() {
        GoogleProvider.benchFor(1, null) shouldBe 30.seconds
        GoogleProvider.benchFor(2, null) shouldBe 60.seconds
        GoogleProvider.benchFor(9, null) shouldBe 10.minutes
        GoogleProvider.benchFor(1, 90.seconds) shouldBe 90.seconds
        GoogleProvider.benchFor(3, 5.seconds) shouldBe 120.seconds
    }

    @Test
    fun textOver1800EncodedBytesIsPosted() = runTest {
        val methods = mutableListOf<String>()
        val host = host { request ->
            methods += request.method.value
            if (request.method.value == "POST") {
                request.form("q")!!.length shouldBe 700
                request.url.parameters["q"] shouldBe null
            }
            json("""[[["T.","S."]],null,"ru"]""")
        }
        GoogleProvider.translate(TranslationRequest("short", "en"), host)
        // 700 Cyrillic letters are 4200 url-encoded bytes.
        GoogleProvider.translate(TranslationRequest("ж".repeat(700), "en"), host).text shouldBe "T."
        methods shouldBe listOf("GET", "POST")
    }

    @Test
    fun aServerErrorMovesToTheNextHostAndTwoNetworkErrorsEndTheAttempt() = runTest {
        val hosts = mutableListOf<String>()
        val flaky = host { request ->
            hosts += request.url.host
            if (request.url.host == "clients5.google.com") respond("oops", HttpStatusCode.BadGateway) else json(sample)
        }
        GoogleProvider.translate(TranslationRequest("猫", "en"), flaky).text shouldBe "Cat"
        hosts shouldBe listOf("clients5.google.com", "translate.googleapis.com")

        var attempts = 0
        val offline = host {
            attempts++
            throw IllegalStateException("no route to host")
        }
        shouldThrow<TranslationException> { GoogleProvider.translate(TranslationRequest("猫", "en"), offline) }.code shouldBe ErrorCode.Network
        attempts shouldBe 2
    }

    @Test
    fun tldIsValidatedBeforeItGoesIntoAUrl() = runTest {
        GoogleProvider.safeTld("co.jp") shouldBe "co.jp"
        GoogleProvider.safeTld("evil.com/x?") shouldBe "com"
        val hosts = mutableListOf<String>()
        val host = host(options = mapOf("tld" to "co.jp")) { request ->
            hosts += request.url.host
            if (request.url.host.endsWith("co.jp")) json(sample) else respond("<html>consent</html>", HttpStatusCode.OK, HTML)
        }
        GoogleProvider.translate(TranslationRequest("猫", "en"), host)
        hosts.last() shouldBe "translate.google.co.jp"
    }

    @Test
    fun detectReadsTheSourceSlot() = runTest {
        val host = host { json(sample) }
        GoogleProvider.detect("猫", host) shouldBe Detection("ja", 0.98f)
        GoogleProvider.detect("?", host { json("""[[["?","?"]],null,"und"]""") }) shouldBe null
    }
}

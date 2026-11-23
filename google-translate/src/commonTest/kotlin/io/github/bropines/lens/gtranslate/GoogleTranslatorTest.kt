package io.github.bropines.lens.gtranslate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class GoogleTranslatorTest {
    // Shape of a real reply with dt=t,rm,ld,bd for "猫".
    private val sample = """
        [[["Cat","猫",null,null,10],[null,null,"Kot","Neko"]],
         [["noun",["cat","kitty"],[["cat",["猫","ネコ"]]],"猫",1]],
         "ja",null,null,null,0.98,[],[["ja"],null,[0.98],["ja"]]]
    """.trimIndent()

    private val json = headersOf("Content-Type", "application/json; charset=UTF-8")
    private val html = headersOf("Content-Type", "text/html")

    @Test
    fun parsesTextLanguageTransliterationAndDictionary() {
        val parsed = TranslateResponseParser.parse(sample)
        parsed.text shouldBe "Cat"
        parsed.sourceLanguage shouldBe "ja"
        parsed.confidence shouldBe 0.98f
        parsed.transliteration shouldBe "Kot"
        parsed.sourceTransliteration shouldBe "Neko"
        parsed.dictionary.single().terms shouldBe listOf("cat", "kitty")
        parsed.dictionary.single().backTranslations.single() shouldBe ("cat" to listOf("猫", "ネコ"))
    }

    @Test
    fun missingSlotsGiveLessDetailNotACrash() {
        val parsed = TranslateResponseParser.parse("""[[["Hi","Привет"]]]""")
        parsed.text shouldBe "Hi"
        parsed.sourceLanguage shouldBe null
        parsed.dictionary shouldBe emptyList()
    }

    @Test
    fun aCaptchaOnTheFirstHostFallsThroughToTheNext() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "clients5.google.com") {
                respond("<html>Our systems have detected unusual traffic</html>", HttpStatusCode.TooManyRequests, html)
            } else {
                respond(sample, HttpStatusCode.OK, json)
            }
        }
        val translator = GoogleTranslator(HttpClient(engine))
        translator.translate("猫", "en").text shouldBe "Cat"
        // The benched host is skipped on the next call.
        translator.translate("猫", "en")
        hosts shouldBe listOf("clients5.google.com", "translate.googleapis.com", "translate.googleapis.com")
    }

    @Test
    fun jsonThatMentionsUnusualTrafficIsNotACaptcha() {
        GoogleTranslator.classify(200, "application/json", """[[["unusual traffic /sorry/","x"]]]""") shouldBe GoogleTranslator.Verdict.Ok
        GoogleTranslator.classify(200, "text/html", "<html>/sorry/index</html>") shouldBe GoogleTranslator.Verdict.Captcha
        GoogleTranslator.classify(200, "text/html", "<html>consent</html>") shouldBe GoogleTranslator.Verdict.TryNext
    }

    @Test
    fun allHostsBenchedIsRateLimited() = runTest {
        val engine = MockEngine { respond("<html>/sorry/</html>", HttpStatusCode.TooManyRequests, html) }
        shouldThrow<GoogleTranslateException.RateLimited> { GoogleTranslator(HttpClient(engine)).translate("a", "en") }
    }

    @Test
    fun longTextIsPostedAndChunksAreRejoinedWithTheirWhitespace() = runTest {
        val sentence = "This is a sentence. "
        val text = sentence.repeat(600) + "\n\n" + sentence.repeat(10)
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            respond("""[[["T.","S."]],null,"en"]""", HttpStatusCode.OK, json)
        }
        val result = GoogleTranslator(HttpClient(engine)).translate(text, "ru")
        methods.all { it == "POST" } shouldBe true
        methods.size shouldBe TextChunker.split(text).size
        result.text shouldStartWith "T."
    }

    @Test
    fun chunkerPrefersNewlinesAndKeepsWhitespace() {
        val text = "a".repeat(7000) + "\n" + "b".repeat(7000)
        val chunks = TextChunker.split(text)
        chunks.size shouldBe 2
        chunks[0].body shouldBe "a".repeat(7000)
        chunks[0].trailing shouldBe "\n"
        chunks.joinToString("") { it.leading + it.body + it.trailing } shouldBe text
    }

    @Test
    fun tldIsValidatedBeforeItGoesIntoAUrl() {
        GoogleTranslator.safeTld("co.jp") shouldBe "co.jp"
        GoogleTranslator.safeTld("evil.com/x?") shouldBe "com"
    }
}

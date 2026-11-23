package io.github.bropines.lens.translate

import io.github.bropines.lens.translate.providers.BingWebProvider
import io.github.bropines.lens.translate.providers.DeepLWebProvider
import io.github.bropines.lens.translate.providers.LaraProvider
import io.github.bropines.lens.translate.providers.LingvaProvider
import io.github.bropines.lens.translate.providers.ModernMtProvider
import io.github.bropines.lens.translate.providers.MyMemoryProvider
import io.github.bropines.lens.translate.providers.YandexWebProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class KeylessProvidersTest {
    // ---- Bing ----

    private val bingPage = """
        <html><script>var _G={IG:"8F3A1C2B9D4E4F6A",EventID:"x"};
        var params_AbusePreventionHelper = [1763900000000,"tok-AbC_123",3600000];</script></html>
    """.trimIndent()
    private val bingPageHeaders = headersOf(
        "Content-Type" to listOf("text/html"),
        "Set-Cookie" to listOf("MUID=1234; domain=.bing.com; path=/", "_EDGE_S=F=1; path=/; httponly"),
    )
    private val bingReply = """
        [{"detectedLanguage":{"language":"zh-Hans","score":0.97},
          "translations":[{"text":"Привет","transliteration":{"text":"Privet","script":"Latn"},"to":"ru"}]}]
    """.trimIndent()

    @Test
    fun bingLoadsThePageOnceAndPostsWithItsSessionParameters() = runTest {
        var pages = 0
        val host = host { request ->
            if (request.method.value == "GET") {
                pages++
                respond(bingPage, HttpStatusCode.OK, bingPageHeaders)
            } else {
                request.url.parameters["IG"] shouldBe "8F3A1C2B9D4E4F6A"
                request.url.parameters["IID"] shouldBe "translator.5027"
                request.form("token") shouldBe "tok-AbC_123"
                request.form("key") shouldBe "1763900000000"
                request.form("fromLang") shouldBe "auto-detect"
                request.form("to") shouldBe "ru"
                request.headers["Cookie"] shouldBe "MUID=1234; _EDGE_S=F=1"
                json(bingReply)
            }
        }
        val result = BingWebProvider.translate(TranslationRequest("你好", "ru"), host)
        result.text shouldBe "Привет"
        result.transliteration shouldBe "Privet"
        result.detectedSource shouldBe "zh-Hans"
        result.confidence shouldBe 0.97f
        BingWebProvider.translate(TranslationRequest("你好", "ru"), host)
        pages shouldBe 1
    }

    @Test
    fun bingReloadsAStaleSessionOnceAndReportsA429() = runTest {
        var now = 0L
        var pages = 0
        var posts = 0
        var refuse = true
        val host = host(now = { now }) { request ->
            when {
                request.method.value == "GET" -> {
                    pages++
                    respond(bingPage, HttpStatusCode.OK, bingPageHeaders)
                }
                posts++ == 1 && refuse -> json("""{"statusCode":400,"errorMessage":"token expired"}""")
                else -> json(bingReply)
            }
        }
        BingWebProvider.translate(TranslationRequest("a", "ru"), host)
        // The stored session is refused: one reload, one more post.
        BingWebProvider.translate(TranslationRequest("a", "ru"), host).text shouldBe "Привет"
        pages shouldBe 2
        posts shouldBe 3
        // After 20 minutes the session is not reused at all.
        now += 21 * 60 * 1000
        BingWebProvider.translate(TranslationRequest("a", "ru"), host)
        pages shouldBe 3

        val limited = host { request ->
            if (request.method.value == "GET") respond(bingPage, HttpStatusCode.OK, bingPageHeaders) else json("""{"statusCode":429}""")
        }
        shouldThrow<TranslationException> { BingWebProvider.translate(TranslationRequest("a", "ru"), limited) }.code shouldBe ErrorCode.RateLimited
    }

    @Test
    fun bingPageWithoutSessionParametersIsAChangedResponse() = runTest {
        val host = host { respond("<html>new design</html>", HttpStatusCode.OK, HTML) }
        val error = shouldThrow<TranslationException> { BingWebProvider.translate(TranslationRequest("a", "ru"), host) }
        error.code shouldBe ErrorCode.BadResponse
        shouldThrow<TranslationException> { BingWebProvider.translate(TranslationRequest("a", "be"), host) }.code shouldBe ErrorCode.TargetLanguage
    }

    // ---- Yandex ----

    @Test
    fun yandexNamesItsClientAndReadsTheDetectedPair() = runTest {
        val host = host { request ->
            request.url.parameters["srv"] shouldBe "browser_video_translation"
            request.url.parameters["lang"] shouldBe "ru"
            request.form("text") shouldBe "hello"
            request.headers["Referer"] shouldBe "https://translate.yandex.com/"
            json("""{"code":200,"lang":"en-ru","text":["привет"]}""")
        }
        val result = YandexWebProvider.translate(TranslationRequest("hello", "ru"), host)
        result.text shouldBe "привет"
        result.detectedSource shouldBe "en"

        val pair = host { request ->
            request.url.parameters["lang"] shouldBe "zh-no"
            json("""{"code":200,"lang":"zh-no","text":["hei"]}""")
        }
        YandexWebProvider.translate(TranslationRequest("你好", "nb", source = "zh-Hant"), pair).detectedSource shouldBe "zh-Hans"
    }

    @Test
    fun yandexErrorsArriveWithHttp200() = runTest {
        suspend fun codeFor(body: String, status: Int = 200) = shouldThrow<TranslationException> {
            YandexWebProvider.translate(TranslationRequest("hello", "ru"), host { json(body, status) })
        }.code
        codeFor("""{"code":501,"message":"The specified translation direction is not supported"}""") shouldBe ErrorCode.TargetLanguage
        codeFor("""{"code":413,"message":"Text too long"}""") shouldBe ErrorCode.BadRequest
        codeFor("""{"code":429,"message":"Too many requests"}""") shouldBe ErrorCode.RateLimited
        codeFor("gone", 410) shouldBe ErrorCode.Http
        codeFor("""{"code":200}""") shouldBe ErrorCode.BadResponse
    }

    @Test
    fun yandexAddsADictionaryArticleForAShortQueryOnly() = runTest {
        val paths = mutableListOf<String>()
        val host = host { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/lookup")) {
                request.url.parameters["lang"] shouldBe "en-ru"
                request.url.parameters["text"] shouldBe "cat"
                json(
                    """
                    {"head":{},"def":[{"text":"cat","pos":"noun","ts":"kæt","tr":[
                      {"text":"кошка","pos":"noun","syn":[{"text":"кот"},{"text":"кошечка"}],
                       "mean":[{"text":"feline"}],"ex":[{"text":"black cat","tr":[{"text":"чёрная кошка"}]}]}]}]}
                    """.trimIndent(),
                )
            } else {
                json("""{"code":200,"lang":"en-ru","text":["кошка"]}""")
            }
        }
        val entry = YandexWebProvider.translate(TranslationRequest(" cat ", "ru", dictionary = true), host).dictionary.single()
        entry.partOfSpeech shouldBe "noun"
        entry.transcription shouldBe "kæt"
        entry.terms.single() shouldBe DictionaryTerm("кошка", listOf("feline"), listOf("кот", "кошечка"), listOf("black cat — чёрная кошка"))

        paths.clear()
        YandexWebProvider.translate(TranslationRequest("the cat sat on the mat", "ru", dictionary = true), host).dictionary shouldBe emptyList()
        paths.size shouldBe 1
        YandexWebProvider.isDictionaryQuery("one two three") shouldBe true
        YandexWebProvider.isDictionaryQuery("x".repeat(61)) shouldBe false
    }

    @Test
    fun yandexDictionaryFailureDoesNotFailTheTranslation() = runTest {
        val host = host { request ->
            if (request.url.encodedPath.endsWith("/lookup")) respond("nope", HttpStatusCode.Forbidden) else json("""{"code":200,"lang":"en-ru","text":["кошка"]}""")
        }
        val result = YandexWebProvider.translate(TranslationRequest("cat", "ru", dictionary = true), host)
        result.text shouldBe "кошка"
        result.dictionary shouldBe emptyList()
    }

    @Test
    fun yandexDetects() = runTest {
        val host = host { request ->
            request.url.parameters["text"]!!.length shouldBe 256
            json("""{"code":200,"lang":"tl"}""")
        }
        YandexWebProvider.detect("k".repeat(1000), host) shouldBe Detection("fil")
    }

    // ---- DeepL (web) ----

    @Test
    fun deepLWebSendsTheAppIdentityAndKeepsItsInstanceId() = runTest {
        val store = MemoryKeyValueStore()
        val ids = mutableListOf<String>()
        val host = host(options = mapOf("app_version" to "99.1"), store = store) { request ->
            request.url.toString() shouldBe "https://oneshot-free.www.deepl.com/v1/translate"
            request.headers["User-Agent"]!! shouldContain "DeepL/99.1 (com.linguee.DeepLMobileTranslator; build:"
            val body = request.jsonBody()
            body["text"]!!.jsonArray.single().jsonPrimitive.content shouldBe "hello"
            body.text("target_lang") shouldBe "pt-BR"
            body.containsKey("source_lang") shouldBe false
            body.text("usage_type") shouldBe "translate"
            val app = body["app_information"]!!.jsonObject
            app.text("os") shouldBe "iOS"
            app.text("app_version") shouldBe "99.1"
            ids += app.text("instance_id")!!
            json("""{"translations":[{"detected_source_language":"EN","text":"olá"}]}""")
        }
        val result = DeepLWebProvider.translate(TranslationRequest("hello", "pt"), host)
        result.text shouldBe "olá"
        result.detectedSource shouldBe "en"
        DeepLWebProvider.translate(TranslationRequest("hello", "pt"), host)
        ids.distinct().size shouldBe 1
        ids.first().length shouldBe 36
        store.get("instance_id") shouldBe ids.first()
    }

    @Test
    fun deepLWebErrors() = runTest {
        val limited = host { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7")) }
        val error = shouldThrow<TranslationException> { DeepLWebProvider.translate(TranslationRequest("a", "de"), limited) }
        error.code shouldBe ErrorCode.RateLimited
        error.retryAfter shouldBe 7.seconds

        val refused = shouldThrow<TranslationException> {
            DeepLWebProvider.translate(TranslationRequest("a", "de"), host { respond("Forbidden", HttpStatusCode.Forbidden) })
        }
        refused.code shouldBe ErrorCode.Http
        refused.httpStatus shouldBe 403
        refused.message!! shouldContain "app version"

        shouldThrow<TranslationException> {
            DeepLWebProvider.translate(TranslationRequest("a", "de"), host { json("""{"result":"new format"}""") })
        }.code shouldBe ErrorCode.BadResponse
    }

    // ---- Lara ----

    @Test
    fun laraTranslatesAndReportsAnUnsupportedLanguage() = runTest {
        val host = host { request ->
            val body = request.jsonBody()
            body.text("q") shouldBe "hello"
            body.text("source") shouldBe ""
            body.text("target") shouldBe "it"
            body["instructions"]!!.jsonArray.size shouldBe 0
            request.headers["Origin"] shouldBe "https://laratranslate.com"
            json("""{"status":200,"content":{"translation":"ciao","source_language":"en","quota":{}}}""")
        }
        val result = LaraProvider.translate(TranslationRequest("hello", "it"), host)
        result.text shouldBe "ciao"
        result.detectedSource shouldBe "en"

        val unsupported = host { json("""{"status":400,"error":{"type":"UnsupportedLanguageError","message":"Language xx is not supported"}}""", 400) }
        shouldThrow<TranslationException> { LaraProvider.translate(TranslationRequest("hello", "xx"), unsupported) }.code shouldBe ErrorCode.TargetLanguage
        shouldThrow<TranslationException> { LaraProvider.translate(TranslationRequest("hello", "it"), host { json("""{"status":200}""") }) }
            .code shouldBe ErrorCode.BadResponse
    }

    // ---- ModernMT ----

    @Test
    fun modernMtPostsAFormAndOmitsTheSourceToDetect() = runTest {
        val host = host { request ->
            request.url.encodedQuery shouldBe "_data=routes%2Ftranslate"
            request.form("text") shouldBe "hello"
            request.form("targetLanguage") shouldBe "zh-TW"
            request.form("sourceLanguage") shouldBe null
            request.headers["Origin"] shouldBe "https://www.modernmt.com"
            json("""{"status":"success","value":{"translation":"你好","detectedLanguage":"en"}}""")
        }
        val result = ModernMtProvider.translate(TranslationRequest("hello", "zh-Hant"), host)
        result.text shouldBe "你好"
        result.detectedSource shouldBe "en"

        // Recorded live on 2026-11-23.
        val failed = host {
            json("""{"status":"error","initialValue":{"targetLanguage":"it","text":"hello"},"error":{"":["Operation not allowed: your account status is \"suspended\""]},"fields":["text","targetLanguage"]}""")
        }
        val error = shouldThrow<TranslationException> { ModernMtProvider.translate(TranslationRequest("hello", "it"), failed) }
        error.code shouldBe ErrorCode.BadResponse
        error.message shouldBe "ModernMT: Operation not allowed: your account status is \"suspended\""
        val html = host { respond("<html>Just a moment...</html>", HttpStatusCode.OK, HTML) }
        shouldThrow<TranslationException> { ModernMtProvider.translate(TranslationRequest("hello", "it"), html) }.code shouldBe ErrorCode.BadResponse
    }

    // ---- MyMemory ----

    @Test
    fun myMemoryTranslatesAndSendsTheEmailOnlyWhenTheUserSetIt() = runTest {
        val queries = mutableListOf<String>()
        val reply = """{"responseData":{"translatedText":"Ciao mondo","match":1},"quotaFinished":false,"responseDetails":"","responseStatus":200,"matches":[]}"""
        val plain = host { request ->
            queries += request.url.encodedQuery
            request.url.parameters["langpair"] shouldBe "en|it"
            request.url.parameters["q"] shouldBe "Hello world"
            json(reply)
        }
        val result = MyMemoryProvider.translate(TranslationRequest("Hello world", "it", source = "en"), plain)
        result.text shouldBe "Ciao mondo"
        queries.single() shouldNotContain "de="

        val withEmail = host(options = mapOf("email" to "user@example.org")) { request ->
            request.url.parameters["de"] shouldBe "user@example.org"
            json(reply)
        }
        MyMemoryProvider.translate(TranslationRequest("Hello world", "it", source = "en"), withEmail).text shouldBe "Ciao mondo"
    }

    @Test
    fun myMemoryNeverReturnsItsQuotaWarningAsATranslation() = runTest {
        suspend fun failure(body: String) = shouldThrow<TranslationException> {
            MyMemoryProvider.translate(TranslationRequest("Hello", "it", source = "en"), host { json(body) })
        }
        val warning = failure(
            """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY. NEXT AVAILABLE IN 10 HOURS"},"quotaFinished":false,"responseStatus":200}""",
        )
        warning.code shouldBe ErrorCode.RateLimited
        failure("""{"responseData":{"translatedText":"Ciao"},"quotaFinished":true,"responseStatus":200}""").code shouldBe ErrorCode.RateLimited
        // The real status is in responseStatus, sometimes as a string, under HTTP 200.
        failure("""{"responseData":{"translatedText":""},"responseDetails":"TOO MANY REQUESTS","responseStatus":"429"}""").code shouldBe ErrorCode.RateLimited
        failure("""{"responseData":{"translatedText":"'XX' IS AN INVALID TARGET LANGUAGE"},"responseDetails":"'XX' IS AN INVALID TARGET LANGUAGE","responseStatus":403}""")
            .code shouldBe ErrorCode.TargetLanguage

        var requests = 0
        val host = host {
            requests++
            json("{}")
        }
        shouldThrow<TranslationException> { MyMemoryProvider.translate(TranslationRequest("Hello", "it"), host) }.code shouldBe ErrorCode.SourceLanguage
        requests shouldBe 0
    }

    // ---- Lingva ----

    @Test
    fun lingvaPutsTheTextInThePathAndReadsTheExtras() = runTest {
        val host = host(options = mapOf("instance" to "https://lingva.example/")) { request ->
            request.url.toString() shouldBe "https://lingva.example/api/v1/auto/zh_HANT/a%2Fb%20c%3F"
            json(
                """
                {"translation":"你好","info":{"detectedSource":"en","pronunciation":{"query":"həˈlō","translation":"Nǐ hǎo"},
                 "extraTranslations":[{"type":"interjection","list":[{"word":"你好","meanings":["hello","hi"],"frequency":3}]}]}}
                """.trimIndent(),
            )
        }
        val result = LingvaProvider.translate(TranslationRequest("a/b c?", "zh-Hant"), host)
        result.text shouldBe "你好"
        result.detectedSource shouldBe "en"
        result.transliteration shouldBe "Nǐ hǎo"
        result.dictionary.single().terms.single() shouldBe DictionaryTerm("你好", listOf("hello", "hi"))
    }

    @Test
    fun lingvaTreatsAnEchoAsAnError() = runTest {
        fun echo(text: String, detected: String = "en") = host { json("""{"translation":"$text","info":{"detectedSource":"$detected"}}""") }
        val long = "the quick brown fox"
        shouldThrow<TranslationException> { LingvaProvider.translate(TranslationRequest(long, "ru"), echo(long)) }.code shouldBe ErrorCode.BadResponse
        // Two words may well be a name; text already in the target language is not an echo.
        LingvaProvider.translate(TranslationRequest("New York", "ru"), echo("New York")).text shouldBe "New York"
        LingvaProvider.translate(TranslationRequest(long, "en"), echo(long)).text shouldBe long

        val broken = host { json("""{"error":"Invalid language"}""", 500) }
        shouldThrow<TranslationException> { LingvaProvider.translate(TranslationRequest(long, "ru"), broken) }.code shouldBe ErrorCode.Http
    }
}

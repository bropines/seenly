package io.github.bropines.lens.protocol

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import lens.LensOverlayObjectsResponse
import lens.LensOverlayServerResponse
import kotlin.test.Test

class GoogleCredentialsTest {
    private val session = GoogleCredentials(
        mapOf("SID" to "sid-value", "SAPISID" to "sapisid-value", "__Secure-1PAPISID" to "first-party", "empty" to ""),
    )

    @Test
    fun signsTheWayAGooglePageDoes() {
        // sha1("<time> <SAPISID> <origin>"), one part per SAPISID cookie that is present.
        session.authorization("https://www.google.com", 1_700_000_000) shouldBe
            "SAPISIDHASH 1700000000_688d03defb2aee22eaa6fc75edd1d26d270adf42 " +
            "SAPISID1PHASH 1700000000_da82536d82c5d429c805c24ca28f66691b8bf81f"
        GoogleCredentials(mapOf("SID" to "x")).authorization("https://www.google.com", 1) shouldBe null
    }

    @Test
    fun aSessionNeedsBothASessionCookieAndASapisid() {
        session.isSignedIn shouldBe true
        GoogleCredentials(mapOf("SID" to "x")).isSignedIn shouldBe false
        GoogleCredentials(mapOf("SAPISID" to "x")).isSignedIn shouldBe false
        GoogleCredentials(mapOf("NID" to "x", "SOCS" to "y")).isSignedIn shouldBe false
        GoogleCredentials(mapOf("__Secure-3PSID" to "x", "__Secure-3PAPISID" to "y")).isSignedIn shouldBe true
    }

    @Test
    fun anonymousHeadersAreUntouchedAndSignedOnesAddToThem() {
        val anonymous = LensRequests.headers("k")
        anonymous.map { it.first } shouldBe listOf("Content-Type", "Accept", "User-Agent", "X-Goog-Api-Key")
        LensRequests.headers("k", credentials = null, nowSeconds = 5) shouldBe anonymous

        val signed = LensRequests.headers("k", session, 1_700_000_000)
        signed.take(anonymous.size) shouldBe anonymous
        // Empty cookies are dropped; the rest go out as one header.
        signed shouldContain ("Cookie" to "SID=sid-value; SAPISID=sapisid-value; __Secure-1PAPISID=first-party")
        signed shouldContain ("Origin" to GoogleCredentials.ORIGIN)
        signed shouldContain ("X-Goog-AuthUser" to "0")
        signed.single { it.first == "Authorization" }.second.startsWith("SAPISIDHASH 1700000000_") shouldBe true
        // Cookies that cannot sign still go out, but nothing claims an origin for them.
        LensRequests.headers("k", GoogleCredentials(mapOf("NID" to "n")), 1).map { it.first } shouldBe anonymous.map { it.first } + "Cookie"
    }

    private class Source(var session: GoogleCredentials?) : LensCredentialsSource {
        var rejectedWith: Int? = null
        override fun current() = session
        override fun rejected(status: Int, detail: String) {
            rejectedWith = status
            session = null
        }
    }

    private val image = PreparedImage(byteArrayOf(1, 2, 3), 10, 10)
    private val emptyReply = LensOverlayServerResponse(objects_response = LensOverlayObjectsResponse()).encode()

    @Test
    fun aRefusedSessionIsReportedAndTheRequestRepeatedAnonymously() = runTest {
        val cookies = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cookies += request.headers["Cookie"]
            if (request.headers["Cookie"] != null) respond("", HttpStatusCode.Unauthorized) else respond(emptyReply, HttpStatusCode.OK)
        }
        val source = Source(session)
        val client = LensClient(HttpClient(engine), credentials = source)
        client.scan(image).paragraphs shouldBe emptyList()
        source.rejectedWith shouldBe 401
        cookies shouldBe listOf(session.cookieHeader, null)
        // The source dropped the session, so the next request starts anonymous.
        client.scan(image)
        cookies.last() shouldBe null
        cookies.size shouldBe 3
    }

    @Test
    fun anAcceptedSessionIsSentOnceAndOtherErrorsAreNotBlamedOnIt() = runTest {
        var requests = 0
        val source = Source(session)
        val ok = MockEngine { request ->
            requests++
            (request.headers["Authorization"] != null) shouldBe true
            respond(emptyReply, HttpStatusCode.OK)
        }
        LensClient(HttpClient(ok), credentials = source).scan(image)
        requests shouldBe 1

        val busy = MockEngine { respond("quota", HttpStatusCode.TooManyRequests) }
        runCatching { LensClient(HttpClient(busy), credentials = source).scan(image) }.exceptionOrNull()!!::class shouldBe LensException.Http::class
        source.rejectedWith shouldBe null
    }

    @Test
    fun readsACookieHeader() {
        val expected = mapOf("SID" to "a=b", "HSID" to "c", "__Secure-1PSID" to "d")
        GoogleCookies.parse("Cookie: SID=a=b; HSID=c;  __Secure-1PSID=d") shouldBe expected
        GoogleCookies.parse("  sid junk; SID=a=b; HSID=c\n__Secure-1PSID=d; ") shouldBe expected
        GoogleCookies.parse("") shouldBe emptyMap()
        GoogleCookies.parse("just some words") shouldBe emptyMap()
    }

    @Test
    fun readsNetscapeCookiesTxtKeepingOnlyGoogle() {
        val text = listOf(
            "# Netscape HTTP Cookie File",
            ".google.com\tTRUE\t/\tTRUE\t1800000000\tSID\tsid",
            "#HttpOnly_.google.com\tTRUE\t/\tTRUE\t1800000000\tHSID\thsid",
            "accounts.google.com\tFALSE\t/\tTRUE\t1800000000\t__Host-GAPS\tgaps",
            // Listed before the site-wide one in some exports, after it in others: google.com wins either way.
            "mail.google.com\tFALSE\t/\tTRUE\t1800000000\tSID\tother",
            ".googleapis.com\tTRUE\t/\tTRUE\t1800000000\tAPI\tapi",
            ".google.ru\tTRUE\t/\tTRUE\t1800000000\tNID\tlocal",
            ".notgoogle.com\tTRUE\t/\tTRUE\t1800000000\tSID\tevil",
            ".example.com\tTRUE\t/\tFALSE\t0\tsession\tx",
            "broken line",
        ).joinToString("\r\n")
        GoogleCookies.parse(text) shouldBe mapOf("HSID" to "hsid", "API" to "api", "SID" to "sid")
    }

    @Test
    fun readsCookieEditorJson() {
        val json = """
            [
              {"domain": ".google.com", "name": "SID", "value": "sid", "httpOnly": true, "expirationDate": 1800000000.5},
              {"domain": ".google.com", "name": "SAPISID", "value": "sapisid"},
              {"domain": ".youtube.com", "name": "SID", "value": "other"},
              {"domain": ".google.com", "name": "no value"},
              "not a cookie"
            ]
        """.trimIndent()
        val expected = mapOf("SID" to "sid", "SAPISID" to "sapisid")
        GoogleCookies.parse(json) shouldBe expected
        GoogleCookies.parse("""{"url": "https://google.com", "cookies": $json}""") shouldBe expected
        GoogleCookies.parse("[not json") shouldBe emptyMap()
    }
}

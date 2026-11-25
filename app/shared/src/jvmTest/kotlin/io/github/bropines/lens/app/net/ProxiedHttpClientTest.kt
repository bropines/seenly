package io.github.bropines.lens.app.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The route as OkHttp is told it, and the client end to end against proxies
 * that live in this process: nothing here touches the network.
 */
class ProxiedHttpClientTest {
    private val http = ProxyRoute.Server(ProxyRoute.Server.Type.Http, "proxy.example.com", 8080, "ann", "pw")
    private val socks = ProxyRoute.Server(ProxyRoute.Server.Type.Socks5, "socks.example.com", 1080, "bob", "s3cret")
    private val anywhere = URI("https://www.google.com/")
    private val servers = ArrayList<ServerSocket>()

    @AfterTest
    fun stopServers() = servers.forEach { it.close() }

    // --- the mapping ---

    @Test
    fun aServerMapsToAJavaProxyOfItsTypeLeftUnresolved() {
        val proxy = http.toJavaProxy()!!
        proxy.type() shouldBe Proxy.Type.HTTP
        val address = proxy.address() as InetSocketAddress
        // Unresolved: looking the proxy up is OkHttp's job (and the SOCKS socket's), not the selector's.
        address.isUnresolved shouldBe true
        address.hostString shouldBe "proxy.example.com"
        address.port shouldBe 8080

        socks.toJavaProxy()!!.type() shouldBe Proxy.Type.SOCKS
        ProxyRoute.Direct.toJavaProxy() shouldBe Proxy.NO_PROXY
    }

    @Test
    fun noProxyIsDirectEvenWhenTheSystemHasOne() {
        val system = FixedSelector(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system.proxy", 3128)))
        RouteProxySelector({ ProxyRoute.Direct }, { system }).select(anywhere) shouldContainExactly listOf(Proxy.NO_PROXY)
        system.asked shouldBe 0
    }

    @Test
    fun systemFollowsThePlatformSelector() {
        val theirs = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system.proxy", 3128))
        RouteProxySelector({ ProxyRoute.System }, { FixedSelector(theirs) }).select(anywhere) shouldContainExactly listOf(theirs)
        // A platform without a selector, or one with no answer, means a direct connection.
        RouteProxySelector({ ProxyRoute.System }, { null }).select(anywhere) shouldContainExactly listOf(Proxy.NO_PROXY)
    }

    @Test
    fun theSelectorAnswersFromTheRouteOfTheMoment() {
        var route: ProxyRoute = ProxyRoute.Direct
        val selector = RouteProxySelector({ route }, { null })
        selector.select(anywhere).single() shouldBe Proxy.NO_PROXY
        route = socks
        selector.select(anywhere).single().type() shouldBe Proxy.Type.SOCKS
    }

    @Test
    fun anUnusableProxyIsNeverSwappedForADirectConnection() {
        val selector = RouteProxySelector({ ProxyRoute.Unusable(ProxyProblem.BadPort) }, { null })
        shouldThrow<IOException> { selector.select(anywhere) }
    }

    @Test
    fun socksCredentialsGoOnlyToTheirOwnProxy() {
        SocksCredentials.register { socks }.use {
            val answer = SocksCredentials.credentialsFor("SOCKS5", "SOCKS.example.com", 1080)!!
            answer.userName shouldBe "bob"
            String(answer.password) shouldBe "s3cret"
            // Another port, another host, or a prompt that is not a SOCKS5 sign-in: nothing.
            SocksCredentials.credentialsFor("SOCKS5", "socks.example.com", 1081) shouldBe null
            SocksCredentials.credentialsFor("SOCKS5", "elsewhere.example.com", 1080) shouldBe null
            SocksCredentials.credentialsFor("https", "socks.example.com", 1080) shouldBe null
            SocksCredentials.credentialsFor(null, "socks.example.com", 1080) shouldBe null
        }
        // And nothing once the client that used the proxy is closed.
        SocksCredentials.credentialsFor("SOCKS5", "socks.example.com", 1080) shouldBe null
    }

    @Test
    fun anHttpProxysPasswordIsNotOfferedToSocksPrompts() {
        SocksCredentials.register { http }.use {
            SocksCredentials.credentialsFor("SOCKS5", "proxy.example.com", 8080) shouldBe null
        }
    }

    // --- the client, end to end ---

    @Test
    fun aRequestGoesThroughTheHttpProxyAndSignsIn(): Unit = runBlocking {
        val proxy = FakeHttpProxy("one", user = "ann", password = "pw")
        val client = proxiedHttpClient(MutableStateFlow(proxy.route("ann", "pw"))) {}
        try {
            // The name resolves nowhere: an answer can only have come from the proxy.
            client.client.get("http://origin.invalid/hello").bodyAsText() shouldBe "via one"
            proxy.requests.last() shouldStartWith "GET http://origin.invalid/hello"
            proxy.challenged shouldBe true
        } finally {
            client.close()
        }
    }

    @Test
    fun aWrongPasswordFailsInsteadOfLooping(): Unit = runBlocking {
        val proxy = FakeHttpProxy("one", user = "ann", password = "pw")
        val client = proxiedHttpClient(MutableStateFlow(proxy.route("ann", "wrong"))) {}
        try {
            client.client.get("http://origin.invalid/").status.value shouldBe 407
        } finally {
            client.close()
        }
    }

    @Test
    fun aChangeOfRouteTakesEffectOnTheSameClient(): Unit = runBlocking {
        val first = FakeHttpProxy("one")
        val second = FakeHttpProxy("two")
        val route = MutableStateFlow<ProxyRoute?>(first.route())
        val client = proxiedHttpClient(route) {}
        try {
            client.client.get("http://origin.invalid/").bodyAsText() shouldBe "via one"

            route.value = second.route()
            client.forgetConnections()
            client.client.get("http://origin.invalid/").bodyAsText() shouldBe "via two"

            // A proxy that cannot be used stops requests; it is not skipped.
            route.value = ProxyRoute.Unusable(ProxyProblem.NoHost)
            client.forgetConnections()
            shouldThrow<IOException> { client.client.get("http://origin.invalid/") }
        } finally {
            client.close()
        }
    }

    @Test
    fun aRequestWaitsForTheSettingsInsteadOfGoingOutDirectly(): Unit = runBlocking {
        val proxy = FakeHttpProxy("late")
        val route = MutableStateFlow<ProxyRoute?>(null)
        val client = proxiedHttpClient(route) {}
        try {
            thread {
                Thread.sleep(300)
                route.value = proxy.route()
            }
            client.client.get("http://origin.invalid/").bodyAsText() shouldBe "via late"
        } finally {
            client.close()
        }
    }

    @Test
    fun aSocks5ProxyGetsItsUserNamePasswordAndTheHostByName(): Unit = runBlocking {
        val proxy = FakeSocks5Proxy()
        val route = ProxyRoute.Server(ProxyRoute.Server.Type.Socks5, "127.0.0.1", proxy.port, "bob", "s3cret")
        val client = proxiedHttpClient(MutableStateFlow(route)) {}
        try {
            client.client.get("http://origin.invalid/").bodyAsText() shouldBe "via socks"
            proxy.signedIn shouldBe ("bob" to "s3cret")
            // The name itself reached the proxy: it was not looked up on this side.
            proxy.destination shouldBe "origin.invalid:80"
        } finally {
            client.close()
        }
    }

    private class FixedSelector(private val proxy: Proxy) : ProxySelector() {
        var asked = 0
        override fun select(uri: URI): List<Proxy> = listOf(proxy).also { asked++ }
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }

    /** A proxy that answers every request itself with "via [name]", after a Basic sign-in when it has a [user]. */
    private inner class FakeHttpProxy(private val name: String, private val user: String? = null, private val password: String = "") {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { servers += it }
        val requests = CopyOnWriteArrayList<String>()

        @Volatile
        var challenged = false

        fun route(username: String = "", password: String = "") =
            ProxyRoute.Server(ProxyRoute.Server.Type.Http, "127.0.0.1", server.localPort, username, password)

        init {
            serve(server) { socket ->
                val head = socket.getInputStream().readHead()
                requests += head.lineSequence().first()
                val expected = user?.let { "Basic " + Base64.getEncoder().encodeToString("$it:$password".toByteArray()) }
                val given = head.lineSequence().firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
                val answer = if (expected != null && given != expected) {
                    challenged = true
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    val body = "via $name"
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                }
                socket.getOutputStream().apply { write(answer.toByteArray()); flush() }
            }
        }
    }

    /** A SOCKS5 server that insists on a user name and password, then plays the destination itself. */
    private inner class FakeSocks5Proxy {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { servers += it }
        val port: Int get() = server.localPort

        @Volatile
        var signedIn: Pair<String, String>? = null

        @Volatile
        var destination: String? = null

        init {
            serve(server) { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                // Greeting: version, then the methods on offer. Answer: user name and password (2).
                input.read()
                input.readNBytes(input.read())
                output.write(byteArrayOf(5, 2))
                // RFC 1929: version 1, the user name, the password.
                input.read()
                val user = String(input.readNBytes(input.read()))
                val password = String(input.readNBytes(input.read()))
                signedIn = user to password
                output.write(byteArrayOf(1, 0))
                // CONNECT: version, command, reserved, address type, address, port.
                input.readNBytes(3)
                val host = when (input.read()) {
                    3 -> String(input.readNBytes(input.read()))
                    1 -> InetAddress.getByAddress(input.readNBytes(4)).hostAddress
                    else -> InetAddress.getByAddress(input.readNBytes(16)).hostAddress
                }
                val portBytes = input.readNBytes(2)
                destination = "$host:${(portBytes[0].toInt() and 0xff) * 256 + (portBytes[1].toInt() and 0xff)}"
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                // From here on the connection is the destination's.
                input.readHead()
                val body = "via socks"
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                output.flush()
            }
        }
    }

    /** Hands each connection to [handle] until the server is closed. */
    private fun serve(server: ServerSocket, handle: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread(isDaemon = true) { runCatching { socket.use(handle) } }
            }
        }
    }

    /** An HTTP request's head, up to the blank line. */
    private fun InputStream.readHead(): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = read()
            if (next < 0) break
            head.append(next.toChar())
        }
        return head.toString()
    }
}

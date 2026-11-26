package io.github.bropines.lens.app.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Interceptor
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/*
 * The proxied client, for Android and the desktop alike: both run OkHttp on a
 * JVM. This directory is compiled into both targets (see build.gradle.kts);
 * Kotlin has no shared source set for the two that can see java.net.
 */

internal actual fun proxiedHttpClient(route: StateFlow<ProxyRoute?>, configure: HttpClientConfig<*>.() -> Unit): ProxiedHttpClient {
    // Called on OkHttp's own threads, where waiting a moment for the settings is harmless.
    val current: () -> ProxyRoute = {
        route.value
            ?: runBlocking { withTimeoutOrNull(SETTINGS_WAIT_MS) { route.filterNotNull().first() } }
            ?: throw IOException("The proxy settings could not be read")
    }
    // A pool of our own, to be able to empty it when the route changes.
    val pool = ConnectionPool()
    val socksCredentials = SocksCredentials.register { route.value }
    val client = HttpClient(OkHttp) {
        engine {
            config {
                connectionPool(pool)
                // HTTP/2 pings find a connection that died under a request (a network switch,
                // a frozen app) in seconds, so it fails and can be retried instead of hanging.
                pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
                addInterceptor(RefuseUnusableProxy(current))
                proxySelector(RouteProxySelector(current))
                proxyAuthenticator(HttpProxyAuthenticator(current))
            }
        }
        configure()
    }
    return ProxiedHttpClient(
        client = client,
        // Idle connections only: a request in flight finishes the way it started.
        forgetConnections = pool::evictAll,
        onClose = {
            socksCredentials.close()
            client.close()
            pool.evictAll()
        },
    )
}

private const val SETTINGS_WAIT_MS = 10_000L

/** Fails a request before it connects anywhere when a proxy is asked for and is not usable. */
internal class RefuseUnusableProxy(private val route: () -> ProxyRoute) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        (route() as? ProxyRoute.Unusable)?.let { throw IOException(it.message()) }
        return chain.proceed(chain.request())
    }
}

private fun ProxyRoute.Unusable.message() = when (problem) {
    ProxyProblem.NoHost -> "The proxy has no address"
    ProxyProblem.BadHost -> "The proxy address is not a host name"
    ProxyProblem.BadPort -> "The proxy has no valid port"
}

/** The proxy this route stands for, or null when the route names none itself. */
internal fun ProxyRoute.toJavaProxy(): Proxy? = when (this) {
    ProxyRoute.Direct -> Proxy.NO_PROXY
    is ProxyRoute.Server -> Proxy(
        if (type == ProxyRoute.Server.Type.Http) Proxy.Type.HTTP else Proxy.Type.SOCKS,
        // Unresolved: OkHttp looks an HTTP proxy up itself, and the SOCKS socket its own server.
        InetSocketAddress.createUnresolved(host, port),
    )
    ProxyRoute.System, is ProxyRoute.Unusable -> null
}

/**
 * Answers OkHttp's "which proxy for this address?" from the route in force at
 * that moment. OkHttp asks once per new connection, which is what makes a
 * change of settings take effect on a client that is already built.
 *
 * Through an HTTP or SOCKS5 proxy OkHttp hands the destination over by name,
 * so the proxy resolves it and no DNS lookup for it leaves this device.
 */
internal class RouteProxySelector(
    private val route: () -> ProxyRoute,
    /** The platform's own selector: Android's follows the network's proxy, Java's its proxy properties. */
    private val system: () -> ProxySelector? = { getDefault() },
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = when (val now = route()) {
        ProxyRoute.System -> system()?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
        is ProxyRoute.Unusable -> throw IOException(now.message())
        else -> listOf(now.toJavaProxy() ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
        // Only the system's selector keeps score of its proxies; ours has one answer.
        if (route() == ProxyRoute.System) system()?.connectFailed(uri, address, failure)
    }
}

/**
 * Signs in to an HTTP proxy with Basic credentials. OkHttp calls this when the
 * proxy answers 407, and ahead of time for the CONNECT that opens an HTTPS tunnel.
 */
internal class HttpProxyAuthenticator(private val current: () -> ProxyRoute) : okhttp3.Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
        val server = (current() as? ProxyRoute.Server)
            ?.takeIf { it.type == ProxyRoute.Server.Type.Http && it.hasCredentials } ?: return null
        // Already sent and refused: asking again with the same password would only loop.
        if (response.request.header(PROXY_AUTHORIZATION) != null) return null
        return response.request.newBuilder()
            .header(PROXY_AUTHORIZATION, Credentials.basic(server.username, server.password))
            .build()
    }

    private companion object {
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    }
}

/**
 * The user name and password for a SOCKS5 proxy. OkHttp's proxy authenticator
 * is for HTTP proxies only: a SOCKS5 sign-in happens inside the JDK's socket,
 * which asks the process-wide [Authenticator] and nothing else.
 *
 * So this is that authenticator, kept as narrow as it can be: it answers only
 * a SOCKS5 prompt, and only from the very host and port of a proxy a client of
 * ours is routed through. Any other prompt (an HTTP 401, another library's
 * proxy) gets no answer from it. Nothing else in the app installs one.
 */
internal object SocksCredentials : Authenticator() {
    private val routes = CopyOnWriteArrayList<() -> ProxyRoute?>()

    /** Offers [route]'s credentials while the returned handle is open. */
    fun register(route: () -> ProxyRoute?): AutoCloseable {
        routes += route
        setDefault(this)
        return AutoCloseable { routes -= route }
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? =
        credentialsFor(requestingProtocol, requestingHost, requestingPort)

    internal fun credentialsFor(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        if (!protocol.equals("SOCKS5", ignoreCase = true)) return null
        val server = routes.firstNotNullOfOrNull { route ->
            (route() as? ProxyRoute.Server)?.takeIf {
                it.type == ProxyRoute.Server.Type.Socks5 && it.hasCredentials && it.port == port && it.host.equals(host, ignoreCase = true)
            }
        } ?: return null
        return PasswordAuthentication(server.username, server.password.toCharArray())
    }
}

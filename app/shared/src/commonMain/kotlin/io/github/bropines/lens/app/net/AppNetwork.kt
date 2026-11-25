package io.github.bropines.lens.app.net

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.data.ProxyPrefs
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.platform.PlatformServices
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * The app's one way out to the network: the HTTP client everything shares
 * (Lens scans and search, the text translators, the updater), sent through the
 * proxy the user set, if any.
 *
 * The client is built once and asks for the route every time it opens a
 * connection, so a change in Settings needs no restart and no new client: the
 * next connection takes the new route, and the idle ones kept open for the old
 * route are dropped. Rebuilding the client instead would mean every holder of
 * it (the Lens client, the translators, the updater) had to be rebuilt too.
 */
@SingleIn(AppScope::class)
@Inject
class AppNetwork(services: PlatformServices, private val settings: SettingsRepository) {
    /** The password with the server it was typed for: it is never offered to another one. */
    @Serializable
    private data class StoredPassword(val host: String, val port: Int, val username: String, val password: String)

    private val log = Logger.withTag("Network")
    private val secrets = services.secrets
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val stored = MutableStateFlow(
        runCatching { secrets.read(SECRET)?.let { json.decodeFromString(StoredPassword.serializer(), it) } }.getOrNull(),
    )

    /**
     * The route in force; null until the settings have been read from disk.
     * The engine waits for it rather than guess: a first request sent directly
     * would defeat the proxy for exactly the people who need it.
     */
    private val route: StateFlow<ProxyRoute?> =
        combine(settings.settings.map { it.proxy }, stored) { prefs, secret -> prefs.toRoute(secret.passwordFor(prefs)) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val proxied = proxiedHttpClient(route) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // Engines set their own, shorter, timeouts; an LLM may take up to two minutes.
            requestTimeoutMillis = 130_000
        }
    }

    /** The client for every request the app makes. */
    val http: HttpClient get() = proxied.client

    /** Whether this platform keeps the password encrypted (Android) or as a private file (the desktop). */
    val encrypted: Boolean = secrets.encrypted

    init {
        // A kept-alive connection would carry the next request the old way for minutes.
        scope.launch { route.filterNotNull().drop(1).collect { proxied.forgetConnections() } }
    }

    /** The password saved for the server in [prefs], or empty. */
    fun passwordFor(prefs: ProxyPrefs): String = stored.value.passwordFor(prefs)

    /** Whether a password is saved for the proxy in use, for the export to offer it. */
    fun hasPassword(): Boolean = passwordFor(settings.current.value.proxy).isNotEmpty()

    /** Puts [prefs] in force, with [password] for its server (empty forgets it). */
    suspend fun save(prefs: ProxyPrefs, password: String) {
        val clean = prefs.copy(host = prefs.cleanHost, username = prefs.username.trim())
        savePassword(clean, password)
        settings.update { it.copy(proxy = clean) }
    }

    /** Keeps [password] for the server in [prefs]; empty or no user name forgets what was kept. */
    fun savePassword(prefs: ProxyPrefs, password: String) {
        val next = if (password.isEmpty() || prefs.username.isBlank()) {
            null
        } else {
            StoredPassword(prefs.cleanHost, prefs.port, prefs.username.trim(), password)
        }
        stored.value = next
        runCatching { secrets.write(SECRET, next?.let { json.encodeToString(StoredPassword.serializer(), it) }) }
            .onFailure { log.w(it) { "proxy password not saved" } }
    }

    /**
     * One small request through [prefs] as they are on screen, saved or not:
     * a client of its own, built the same way as the app's, so the answer is
     * about these settings and not about a connection the app already had open.
     */
    suspend fun test(prefs: ProxyPrefs, password: String): ProxyTestResult {
        val tested = prefs.toRoute(password)
        if (tested is ProxyRoute.Unusable) return ProxyTestResult.Unusable(tested.problem)
        return withContext(Dispatchers.Default) {
            val probe = proxiedHttpClient(MutableStateFlow(tested)) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 15_000
                }
                followRedirects = false
            }
            try {
                val started = TimeSource.Monotonic.markNow()
                val status = probe.client.get(TEST_URL).status.value
                val millis = started.elapsedNow().inWholeMilliseconds
                if (status in 200..299) ProxyTestResult.Ok(millis) else ProxyTestResult.Failed("HTTP $status")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProxyTestResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty())
            } finally {
                probe.close()
            }
        }
    }

    private fun StoredPassword?.passwordFor(prefs: ProxyPrefs): String =
        this?.takeIf { it.host.equals(prefs.cleanHost, ignoreCase = true) && it.port == prefs.port && it.username == prefs.username.trim() }
            ?.password.orEmpty()

    private companion object {
        const val SECRET = "proxy-password"

        /** Google's connectivity check: an empty 204, the smallest answer there is. */
        const val TEST_URL = "https://www.google.com/generate_204"
    }
}

/** What the Network page's test found. */
sealed interface ProxyTestResult {
    /** The request came back; [millis] covers connecting, the TLS handshake and the answer. */
    data class Ok(val millis: Long) : ProxyTestResult

    /** The proxy or the network said no; [reason] is their own words. */
    data class Failed(val reason: String) : ProxyTestResult

    /** The settings cannot be tried as they are. */
    data class Unusable(val problem: ProxyProblem) : ProxyTestResult
}

/** An HTTP client that takes its route per connection, and the handles its owner needs. */
internal class ProxiedHttpClient(
    val client: HttpClient,
    /** Drops the idle connections, so the next request connects by the current route. */
    val forgetConnections: () -> Unit,
    private val onClose: () -> Unit,
) {
    fun close() = onClose()
}

/**
 * A client on OkHttp whose every new connection asks [route] where to go.
 * While [route] is still null (the settings are being read) connections wait.
 * [configure] adds the caller's plugins.
 */
internal expect fun proxiedHttpClient(route: StateFlow<ProxyRoute?>, configure: HttpClientConfig<*>.() -> Unit): ProxiedHttpClient

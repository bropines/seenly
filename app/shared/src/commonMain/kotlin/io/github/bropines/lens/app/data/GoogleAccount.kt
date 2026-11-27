package io.github.bropines.lens.app.data

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.net.AppNetwork
import io.github.bropines.lens.app.platform.PlatformServices
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.bropines.lens.protocol.GoogleCookies
import io.github.bropines.lens.protocol.GoogleCredentials
import io.github.bropines.lens.protocol.LensCredentialsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/** A small named-secret store. Calls block briefly; the values are a few kilobytes. */
interface SecretStore {
    /** Whether the secrets are encrypted at rest, so the UI can say truthfully how they are kept. */
    val encrypted: Boolean

    fun read(name: String): String?

    /** A null [value] removes the secret. */
    fun write(name: String, value: String?)
}

/**
 * Secrets as files in the app's private directory, unencrypted: what a
 * desktop has without a system keyring. Android does not use this one.
 */
class FileSecretStore(private val dir: String) : SecretStore {
    override val encrypted: Boolean = false

    private fun file(name: String) = Path(dir, "$name.secret")

    override fun read(name: String): String? = runCatching {
        SystemFileSystem.source(file(name)).buffered().use { it.readString() }
    }.getOrNull()

    override fun write(name: String, value: String?) {
        if (value == null) {
            SystemFileSystem.delete(file(name), mustExist = false)
        } else {
            SystemFileSystem.sink(file(name)).buffered().use { it.writeString(value) }
        }
    }
}

/**
 * The user's own Google account, attached by choice: the cookies of a browser
 * session, kept in the platform's [SecretStore] and sent only to Google, only
 * from this device. With it Lens requests go out signed in; without it, or
 * with the "use for Lens" setting off, everything is anonymous as before.
 */
@OptIn(ExperimentalTime::class)
@SingleIn(AppScope::class)
@Inject
class GoogleAccount(
    services: PlatformServices,
    private val settings: SettingsRepository,
    private val network: AppNetwork,
) : LensCredentialsSource {
    /** What Settings shows of the account. */
    data class State(
        val connected: Boolean = false,
        /** Epoch milliseconds of the last signed request, or null if none yet. */
        val lastUsed: Long? = null,
        /** Google refused the session: requests are anonymous until the user signs in again or retries. */
        val rejected: Boolean = false,
        /** What Google answered when it refused, e.g. `HTTP 401: …`; empty when it has not. */
        val refusal: String = "",
        /** The account's address, which identity delegation signs with; null until found or typed. */
        val email: String? = null,
    )

    @Serializable
    private data class Stored(
        val cookies: Map<String, String>,
        val email: String? = null,
        val lastUsed: Long? = null,
        val rejected: Boolean = false,
        val refusal: String = "",
    )

    private val log = Logger.withTag("GoogleAccount")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = services.secrets
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this platform keeps the cookies encrypted (Android) or as a private file (the desktop). */
    val encrypted: Boolean = store.encrypted

    /** The session as stored. Read from any thread (a scan asks for it), so it is swapped whole, never edited. */
    private val stored = MutableStateFlow(
        store.read(SECRET)?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() },
    )

    private val _state = MutableStateFlow(stateOf(stored.value))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _refused = MutableStateFlow(false)
    /** Set when Google has just refused the session, for the UI to say so once. */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    fun refusalShown() {
        _refused.value = false
    }

    /**
     * Connects the account whose cookies are in [text] (a `Cookie:` header,
     * a cookies.txt, or a cookie editor's JSON). False when [text] holds no
     * signed-in Google session, and then nothing changes.
     */
    fun connect(text: String): Boolean {
        val parsed = GoogleCredentials(GoogleCookies.parse(text))
        if (!parsed.isSignedIn) return false
        save(Stored(parsed.cookies))
        // The address is not in the cookies: Google says it to the session itself.
        scope.launch { detectEmail() }
        return true
    }

    /** Sets the account's address by hand; blank forgets it. */
    fun setEmail(value: String?) {
        stored.value?.let { save(it.copy(email = value?.trim()?.takeIf(String::isNotEmpty), rejected = false, refusal = "")) }
    }

    /**
     * Asks Google which account the stored session is, the way Chrome does
     * (`ListAccounts`), and keeps its address. Null when that failed.
     */
    suspend fun detectEmail(): String? {
        val s = stored.value ?: return null
        val found = runCatching {
            val reply = network.http.get(LIST_ACCOUNTS) {
                headers {
                    append("Cookie", GoogleCredentials(s.cookies).cookieHeader)
                    append("Origin", GoogleCredentials.ORIGIN)
                }
            }
            if (!reply.status.isSuccess()) null else EMAIL.find(reply.bodyAsText())?.value
        }.onFailure { log.w(it) { "account address not found" } }.getOrNull()
        if (found != null) stored.value?.takeIf { it.cookies == s.cookies }?.let { save(it.copy(email = found, rejected = false, refusal = "")) }
        return found
    }

    /** Forgets the cookies. The session itself stays valid at Google until signed out there. */
    fun disconnect() = save(null)

    /** Offers a refused session again: the refusal may have been Google's passing mood. */
    fun retry() {
        stored.value?.let { save(it.copy(rejected = false, refusal = "")) }
    }

    /** The session as a `Cookie:` header value, for an export the user asked to include it in. */
    fun exportCookies(): String? = stored.value?.let { GoogleCredentials(it.cookies).cookieHeader }

    override fun current(): GoogleCredentials? {
        if (!settings.current.value.account.useForLens) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val s = stored.value?.takeUnless { it.rejected } ?: return null
        // "Last used" is for a person to glance at: once an hour is plenty, and spares a write per scan.
        if (now - (s.lastUsed ?: 0) > 1.hours.inWholeMilliseconds) save(s.copy(lastUsed = now))
        return GoogleCredentials(s.cookies, s.email)
    }

    override fun rejected(status: Int, detail: String) {
        log.w { "Google refused the account session (HTTP $status); continuing anonymously: $detail" }
        stored.value?.takeUnless { it.rejected }?.let {
            save(it.copy(rejected = true, refusal = "HTTP $status" + summarize(detail).let { d -> if (d.isEmpty()) "" else ": $d" }))
            _refused.value = true
        }
    }

    private fun save(next: Stored?) {
        stored.value = next
        runCatching { store.write(SECRET, next?.let { json.encodeToString(Stored.serializer(), it) }) }
            .onFailure { log.w(it) { "account not saved" } }
        _state.value = stateOf(next)
    }

    private fun stateOf(s: Stored?) =
        State(connected = s != null, lastUsed = s?.lastUsed, rejected = s?.rejected == true, refusal = s?.refusal.orEmpty(), email = s?.email)

    /**
     * Google's error body, shortened to what a person can read: the `message`
     * and `status` (and a `reason`) of a Google API error, else the raw text.
     */
    private fun summarize(body: String): String {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
            ?: return body.trim().take(200)
        fun field(name: String) = (error[name] as? JsonPrimitive)?.contentOrNull
        val reason = runCatching {
            error["details"]?.jsonArray?.firstNotNullOfOrNull { (it.jsonObject["reason"] as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        return listOfNotNull(field("status"), reason, field("message")).distinct().joinToString(" · ")
    }

    private companion object {
        const val SECRET = "google-account"
        /** Chrome's own question: which accounts this cookie session is signed in to. */
        const val LIST_ACCOUNTS = "https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard"
        val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    }
}

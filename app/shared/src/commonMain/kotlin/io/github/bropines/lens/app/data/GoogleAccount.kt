package io.github.bropines.lens.app.data

import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.bropines.lens.app.platform.PlatformServices
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
class GoogleAccount(services: PlatformServices, private val settings: SettingsRepository) : LensCredentialsSource {
    /** What Settings shows of the account. */
    data class State(
        val connected: Boolean = false,
        /** Epoch milliseconds of the last signed request, or null if none yet. */
        val lastUsed: Long? = null,
        /** Google refused the session: requests are anonymous until the user signs in again or retries. */
        val rejected: Boolean = false,
    )

    @Serializable
    private data class Stored(val cookies: Map<String, String>, val lastUsed: Long? = null, val rejected: Boolean = false)

    private val log = Logger.withTag("GoogleAccount")
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
        return true
    }

    /** Forgets the cookies. The session itself stays valid at Google until signed out there. */
    fun disconnect() = save(null)

    /** Offers a refused session again: the refusal may have been Google's passing mood. */
    fun retry() {
        stored.value?.let { save(it.copy(rejected = false)) }
    }

    /** The session as a `Cookie:` header value, for an export the user asked to include it in. */
    fun exportCookies(): String? = stored.value?.let { GoogleCredentials(it.cookies).cookieHeader }

    override fun current(): GoogleCredentials? {
        if (!settings.current.value.account.useForLens) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val s = stored.value?.takeUnless { it.rejected } ?: return null
        // "Last used" is for a person to glance at: once an hour is plenty, and spares a write per scan.
        if (now - (s.lastUsed ?: 0) > 1.hours.inWholeMilliseconds) save(s.copy(lastUsed = now))
        return GoogleCredentials(s.cookies)
    }

    override fun rejected(status: Int) {
        log.w { "Google refused the account session (HTTP $status); continuing anonymously" }
        stored.value?.takeUnless { it.rejected }?.let {
            save(it.copy(rejected = true))
            _refused.value = true
        }
    }

    private fun save(next: Stored?) {
        stored.value = next
        runCatching { store.write(SECRET, next?.let { json.encodeToString(Stored.serializer(), it) }) }
            .onFailure { log.w(it) { "account not saved" } }
        _state.value = stateOf(next)
    }

    private fun stateOf(s: Stored?) = State(connected = s != null, lastUsed = s?.lastUsed, rejected = s?.rejected == true)

    private companion object {
        const val SECRET = "google-account"
    }
}

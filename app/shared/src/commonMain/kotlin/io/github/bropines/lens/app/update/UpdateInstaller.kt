package io.github.bropines.lens.app.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.HashingSink
import okio.blackholeSink
import okio.buffer

/**
 * How a build replaces itself with a downloaded APK. A platform has one only
 * when this copy may update itself: not a debug build (another app id, which a
 * release would install beside, not over), not a copy a store keeps up to
 * date, not the desktop. Without one an update is a link to its release page.
 */
interface UpdateInstaller {
    /** `google` or `foss`: which APK of a release is this build's. */
    val flavor: String

    /** A private directory the system's installer can be handed files from. */
    val downloadDir: String

    /** Whether the user has allowed this app to install packages. */
    fun canInstall(): Boolean

    /** Opens the system's "Install unknown apps" page for this app; only the user can allow it. */
    fun openInstallPermission()

    /** Hands [apkPath] to the system's installer, which asks the user to confirm. */
    fun install(apkPath: String)
}

/** Fetches a release's APK into [UpdateInstaller.downloadDir] and checks it against the release's SHA256SUMS. */
class UpdateDownloader(private val http: HttpClient) {
    private val fs = SystemFileSystem

    /**
     * The verified APK of [release] for [flavor] in [dir], downloaded unless a
     * verified copy is already there. [onProgress] gets 0..1, or null when the
     * server did not say how much is coming.
     *
     * Fails closed: a release without checksums, or a file that does not match
     * them, is never returned.
     */
    suspend fun fetch(release: Release, flavor: String, dir: String, onProgress: (Float?) -> Unit): String = withContext(Dispatchers.IO) {
        val apk = UpdateCheck.apkFor(release, flavor) ?: throw UpdateException("the release has no $flavor APK")
        val sums = UpdateCheck.checksumsOf(release) ?: throw UpdateException("the release has no ${UpdateCheck.CHECKSUMS}")
        val expected = UpdateCheck.parseChecksums(http.get(sums.url).also { it.requireSuccess() }.bodyAsText())[apk.name]
            ?: throw UpdateException("${UpdateCheck.CHECKSUMS} does not list ${apk.name}")

        val folder = Path(dir)
        fs.createDirectories(folder)
        val target = Path(folder, apk.name)
        // Older downloads are of no use once a newer one is wanted.
        fs.list(folder).filter { it != target }.forEach { fs.delete(it, mustExist = false) }
        if (fs.exists(target) && sha256(target) == expected) return@withContext target.toString()

        val partial = Path(folder, "${apk.name}.part")
        val actual = try {
            download(apk, partial, onProgress)
        } catch (e: Throwable) {
            fs.delete(partial, mustExist = false)
            throw e
        }
        if (actual != expected) {
            fs.delete(partial, mustExist = false)
            throw UpdateException("the downloaded file does not match its checksum")
        }
        fs.atomicMove(partial, target)
        target.toString()
    }

    /** Streams [apk] into [to] and returns the SHA-256 of what was written. */
    private suspend fun download(apk: ReleaseAsset, to: Path, onProgress: (Float?) -> Unit): String =
        http.prepareGet(apk.url) {
            // The client's request timeout suits API calls, not tens of megabytes on a slow link.
            timeout { requestTimeoutMillis = Long.MAX_VALUE }
        }.execute { response ->
            response.requireSuccess()
            val total = response.contentLength() ?: apk.size.takeIf { it > 0 }
            val hasher = Sha256()
            fs.sink(to).buffered().use { out ->
                val channel = response.bodyAsChannel()
                val chunk = ByteArray(CHUNK)
                var done = 0L
                onProgress(if (total == null) null else 0f)
                while (true) {
                    val read = channel.readAvailable(chunk, 0, chunk.size)
                    if (read < 0) break
                    out.write(chunk, 0, read)
                    hasher.update(chunk, read)
                    done += read
                    if (total != null) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            hasher.hex()
        }

    private fun sha256(file: Path): String {
        val hasher = Sha256()
        fs.source(file).buffered().use { source ->
            val chunk = ByteArray(CHUNK)
            while (true) {
                val read = source.readAtMostTo(chunk)
                if (read < 0) break
                hasher.update(chunk, read)
            }
        }
        return hasher.hex()
    }

    /** SHA-256 of everything passed to [update]. */
    private class Sha256 {
        private val sink = HashingSink.sha256(blackholeSink())
        private val buffered = sink.buffer()

        fun update(bytes: ByteArray, count: Int) {
            buffered.write(bytes, 0, count)
        }

        fun hex(): String {
            buffered.flush()
            return sink.hash.hex()
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }

    private fun io.ktor.client.statement.HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw UpdateException("GitHub: HTTP ${status.value}")
    }
}

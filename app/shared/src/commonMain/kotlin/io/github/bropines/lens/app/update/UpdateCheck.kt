package io.github.bropines.lens.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A file of a release, as published. */
data class ReleaseAsset(val name: String, val url: String, val size: Long = 0)

/** A release on GitHub, as much of it as the updater reads. */
data class Release(
    val tag: String,
    /** The release notes, in Markdown. */
    val notes: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** A beta by either sign: GitHub's mark, which keeps it from being "latest", or its name. */
    val isBeta: Boolean get() = prerelease || AppVersion.parse(tag)?.isPreRelease == true

    /** The version with no `v`, as the UI names it. */
    val version: String get() = AppVersion.parse(tag)?.toString() ?: tag
}

/**
 * What the in-app updater asks GitHub, and which of the answers it offers.
 *
 * Two channels come out of one repository: releases, and betas of the next
 * version, published as pre-releases under a `-beta.N` tag. Without "Receive
 * beta versions" only releases count, for a beta build too: it is then offered
 * the first release that is newer than it. With it the newest of both by
 * version does.
 */
object UpdateCheck {
    const val REPOSITORY = "https://github.com/bropines/seenly"
    private const val API = "https://api.github.com/repos/bropines/seenly/releases"
    const val CHECKSUMS = "SHA256SUMS"

    /** The page of the release tagged [tag]: where an update is read about, or fetched by hand. */
    fun page(tag: String): String = "$REPOSITORY/releases/tag/$tag"

    /**
     * The newest of [releases] by version, betas among them only when [betas]
     * is set. A draft and a tag that is not a version are never an update.
     */
    fun newest(releases: List<Release>, betas: Boolean): Release? =
        releases.filter { !it.draft && (betas || !it.isBeta) }
            .mapNotNull { release -> AppVersion.parse(release.tag)?.let { it to release } }
            .maxByOrNull { it.first }?.second

    /** What a copy named [installed] is offered out of [releases]: the newest, when it is newer. */
    fun offer(installed: String, releases: List<Release>, betas: Boolean): Release? =
        newest(releases, betas)?.takeIf { isVersionNewer(installed, it.tag) }

    /**
     * The APK of [release] for a build of [flavor] (`google` or `foss`) on a
     * device that runs [abis], best first: the one for the first of them
     * (`Seenly-v0.6.0-google-arm64-v8a-release.apk`), else the universal one,
     * else a release from before the APKs were split (`Seenly-v0.5.2-google-release.apk`).
     * Never the other flavor's: that is a different app id and would install
     * beside this copy, not over it.
     */
    fun apkFor(release: Release, flavor: String, abis: List<String> = emptyList()): ReleaseAsset? {
        fun named(suffix: String) = release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
        return abis.firstNotNullOfOrNull { named("-$flavor-$it-release.apk") }
            ?: named("-$flavor-universal-release.apk")
            ?: named("-$flavor-release.apk")
    }

    /** The release's `SHA256SUMS` file, which lists the hash of every APK. */
    fun checksumsOf(release: Release): ReleaseAsset? = release.assets.firstOrNull { it.name == CHECKSUMS }

    /**
     * `sha256sum` output read: file name to its hash in lower-case hex. A `*`
     * before the name (binary mode) is not part of it; lines that are not a
     * hash and a name are skipped.
     */
    fun parseChecksums(text: String): Map<String, String> = buildMap {
        for (line in text.lineSequence()) {
            val (hash, name) = checksumLine.matchEntire(line.trim())?.destructured ?: continue
            put(name, hash.lowercase())
        }
    }

    private val checksumLine = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")

    /** GitHub's answer read: one release (`/releases/latest`) or a list of them (`/releases`). */
    fun parse(json: String): List<Release> {
        val root = Json.parseToJsonElement(json)
        return (if (root is JsonArray) root else listOf(root)).mapNotNull(::release)
    }

    private fun release(element: JsonElement): Release? {
        val o = element as? JsonObject ?: return null
        val tag = o.text("tag_name") ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { asset ->
            val a = asset as? JsonObject ?: return@mapNotNull null
            ReleaseAsset(
                name = a.text("name") ?: return@mapNotNull null,
                url = a.text("browser_download_url") ?: return@mapNotNull null,
                size = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
        return Release(tag, o.text("body").orEmpty(), o.flag("prerelease"), o.flag("draft"), assets)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /**
     * Asks GitHub for the releases the choice is made from: the ten published
     * last, pre-releases among them, which is where the newest version is.
     * Throws when the request fails.
     */
    suspend fun fetch(http: HttpClient): List<Release> {
        val response = http.get("$API?per_page=10") {
            header("Accept", "application/vnd.github+json")
            // GitHub refuses API requests that name no client.
            header("User-Agent", "Seenly")
        }
        if (!response.status.isSuccess()) throw UpdateException("GitHub: HTTP ${response.status.value}")
        return parse(response.bodyAsText())
    }
}

/** What the updater gives up on. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

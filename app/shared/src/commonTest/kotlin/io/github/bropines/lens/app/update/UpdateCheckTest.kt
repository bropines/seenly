package io.github.bropines.lens.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Which release the updater offers, and which of its files. Two things decide
 * the release: what is installed, and whether the owner asked for betas.
 */
class UpdateCheckTest {
    private fun stable(tag: String) = Release(tag)
    private fun beta(tag: String) = Release(tag, prerelease = true)

    /** GitHub lists the newest first; 0.3.0 is still in beta, 0.2.1 is the latest release. */
    private val whileInBeta = listOf(beta("v0.3.0-beta.2"), beta("v0.3.0-beta.1"), stable("v0.2.1"), stable("v0.2.0"), stable("v0.1.0"))

    /** The same list once 0.3.0 is out. */
    private val afterTheRelease = listOf(stable("v0.3.0")) + whileInBeta

    private fun offered(installed: String, releases: List<Release>, betas: Boolean) = UpdateCheck.offer(installed, releases, betas)?.tag

    @Test
    fun aStableBuildWithoutBetasIsOfferedReleasesOnly() {
        assertEquals("v0.2.1", offered("0.2.0", whileInBeta, betas = false))
        assertNull(offered("0.2.1", whileInBeta, betas = false))
        assertEquals("v0.3.0", offered("0.2.1", afterTheRelease, betas = false))
        assertNull(offered("0.3.0", afterTheRelease, betas = false))
    }

    @Test
    fun aStableBuildWithBetasIsOfferedTheNewestOfBoth() {
        assertEquals("v0.3.0-beta.2", offered("0.2.1", whileInBeta, betas = true))
        // The release outranks its own betas, whichever was published last.
        assertEquals("v0.3.0", offered("0.2.1", afterTheRelease, betas = true))
        assertNull(offered("0.3.0", afterTheRelease, betas = true))
    }

    @Test
    fun aBetaBuildWithoutBetasWaitsForTheReleaseThatIsNewer() {
        // 0.2.1 is the latest release and older than the beta: nothing to go back to.
        assertNull(offered("0.3.0-beta.1", whileInBeta, betas = false))
        assertEquals("v0.3.0", offered("0.3.0-beta.1", afterTheRelease, betas = false))
    }

    @Test
    fun aBetaBuildWithBetasIsOfferedTheNextBetaAndThenTheRelease() {
        assertEquals("v0.3.0-beta.2", offered("0.3.0-beta.1", whileInBeta, betas = true))
        assertNull(offered("0.3.0-beta.2", whileInBeta, betas = true))
        assertEquals("v0.3.0", offered("0.3.0-beta.2", afterTheRelease, betas = true))
    }

    @Test
    fun aPreReleaseOlderThanTheInstalledReleaseIsNotOffered() {
        // A beta of a patch to the line before, published after 0.3.0: first in GitHub's list.
        val aLateBeta = listOf(beta("v0.2.2-beta.1")) + afterTheRelease
        assertNull(offered("0.3.0", aLateBeta, betas = true))
        assertEquals("v0.3.0", offered("0.2.1", aLateBeta, betas = true))
        // A pre-release by its name alone counts as one, whatever GitHub's mark says.
        assertNull(offered("0.2.1", listOf(stable("v0.3.0-rc.1"), stable("v0.2.1")), betas = false))
    }

    @Test
    fun whatIsNotAReleaseIsNotOffered() {
        assertNull(offered("0.1.0", listOf(Release("v9.0.0", draft = true)), betas = true))
        assertNull(offered("0.1.0", listOf(Release("nightly"), Release("latest")), betas = true))
        assertNull(offered("0.1.0", emptyList(), betas = true))
    }

    private val files = Release(
        "v0.2.0",
        assets = listOf(
            ReleaseAsset("SHA256SUMS", "https://example.test/SHA256SUMS"),
            ReleaseAsset("Seenly-v0.2.0-foss-release.apk", "https://example.test/foss.apk", 9_000_000),
            ReleaseAsset("Seenly-v0.2.0-google-release.apk", "https://example.test/google.apk", 12_000_000),
        ),
    )

    @Test
    fun eachFlavorGetsItsOwnApk() {
        assertEquals("https://example.test/google.apk", UpdateCheck.apkFor(files, "google")?.url)
        assertEquals("https://example.test/foss.apk", UpdateCheck.apkFor(files, "foss")?.url)
        assertEquals("SHA256SUMS", UpdateCheck.checksumsOf(files)?.name)
    }

    @Test
    fun aReleaseWithoutThisFlavorsApkOffersNoFile() {
        // Never the other flavor's APK: it is a different app.
        val fossOnly = files.copy(assets = files.assets.filter { "google" !in it.name })
        assertNull(UpdateCheck.apkFor(fossOnly, "google"))
        assertNull(UpdateCheck.apkFor(Release("v0.2.0"), "foss"))
        assertNull(UpdateCheck.checksumsOf(Release("v0.2.0")))
    }

    @Test
    fun readsSha256sumOutput() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = UpdateCheck.parseChecksums(
            """
            $a  Seenly-v0.2.0-foss-release.apk
            $b *Seenly-v0.2.0-google-release.apk

            not a checksum line
            abc123  too-short.apk
            """.trimIndent() + "\r\n",
        )
        assertEquals(mapOf("Seenly-v0.2.0-foss-release.apk" to a, "Seenly-v0.2.0-google-release.apk" to b.lowercase()), sums)
        assertTrue(UpdateCheck.parseChecksums("").isEmpty())
    }

    @Test
    fun readsGitHubsReleaseList() {
        val json = """
            [
              {"tag_name": "v0.3.0-beta.1", "prerelease": true, "draft": false, "body": "### Added\n- Things", "assets": [
                {"name": "Seenly-v0.3.0-beta.1-google-release.apk", "browser_download_url": "https://example.test/g.apk", "size": 123},
                {"name": "broken"}
              ]},
              {"tag_name": "v0.2.0", "prerelease": false, "draft": false, "body": null, "assets": []},
              {"name": "no tag"}
            ]
        """.trimIndent()
        val releases = UpdateCheck.parse(json)
        assertEquals(listOf("v0.3.0-beta.1", "v0.2.0"), releases.map { it.tag })
        assertTrue(releases[0].isBeta)
        assertFalse(releases[1].isBeta)
        assertEquals("### Added\n- Things", releases[0].notes)
        assertEquals("", releases[1].notes)
        assertEquals(listOf(ReleaseAsset("Seenly-v0.3.0-beta.1-google-release.apk", "https://example.test/g.apk", 123)), releases[0].assets)
        assertEquals("0.3.0-beta.1", releases[0].version)
        // `/releases/latest` answers with one object.
        assertEquals(listOf("v0.2.0"), UpdateCheck.parse("""{"tag_name": "v0.2.0"}""").map { it.tag })
    }
}

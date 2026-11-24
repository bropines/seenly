package io.github.bropines.lens.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    private fun v(text: String) = AppVersion.parse(text) ?: error("$text is not a version")

    @Test
    fun readsAVersionWithOrWithoutTheTagsV() {
        assertEquals(AppVersion(0, 2, 0), v("0.2.0"))
        assertEquals(AppVersion(0, 2, 0), v("v0.2.0"))
        assertEquals(AppVersion(0, 2, 0, listOf("beta", "1")), v("v0.2.0-beta.1"))
        assertEquals("0.2.0-beta.1", v("v0.2.0-beta.1").toString())
        // Build metadata is not part of the version.
        assertEquals(v("1.0.0"), v("1.0.0+20261124"))
        assertEquals(v("1.0.0-rc.1"), v("1.0.0-rc.1+abc"))
    }

    @Test
    fun whatIsNotAVersionIsNotRead() {
        for (text in listOf("", "nightly", "latest", "1.2", "1.2.3.4", "1.2.3-", "1.2.3-beta..1", "x1.2.3")) {
            assertNull(AppVersion.parse(text), text)
        }
    }

    @Test
    fun ordersLikeSemanticVersioning() {
        // The example of semver.org, section 11.
        val ascending = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
            "1.0.1-beta.1", "1.0.1", "1.1.0", "1.10.0", "2.0.0",
        ).map(::v)
        for (i in ascending.indices) for (j in ascending.indices) {
            assertEquals(i.compareTo(j), ascending[i].compareTo(ascending[j]).coerceIn(-1, 1), "${ascending[i]} vs ${ascending[j]}")
        }
    }

    @Test
    fun numbersCompareByValueHoweverLong() {
        assertTrue(v("1.0.0-beta.10") > v("1.0.0-beta.9"))
        assertTrue(v("1.0.0-beta.100000000000000000000") > v("1.0.0-beta.99999999999999999999"))
        assertEquals(0, v("1.0.0-beta.01").compareTo(v("1.0.0-beta.1")))
    }

    @Test
    fun newerIsStrictlyLater() {
        assertTrue(isVersionNewer("0.1.0", "v0.2.0"))
        assertTrue(isVersionNewer("0.2.0-beta.1", "v0.2.0"))
        assertTrue(isVersionNewer("0.2.0-beta.1", "v0.2.0-beta.2"))
        assertFalse(isVersionNewer("0.2.0", "v0.2.0"))
        assertFalse(isVersionNewer("0.2.0", "v0.2.0-beta.3"))
        assertFalse(isVersionNewer("0.2.0", "v0.1.9"))
        // A tag that is not a version is never an update; an unreadable installed version is older than any.
        assertFalse(isVersionNewer("0.1.0", "nightly"))
        assertTrue(isVersionNewer("unknown", "v0.1.0"))
    }
}

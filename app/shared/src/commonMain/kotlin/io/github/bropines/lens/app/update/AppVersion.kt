package io.github.bropines.lens.app.update

/**
 * A version as a release names it, read from wherever the app meets one: its
 * own versionName or a tag on GitHub (`v0.2.0`, `0.2.0-beta.1`).
 *
 * Ordered as Semantic Versioning orders them: a pre-release comes before its
 * release (`0.2.0-beta.2 < 0.2.0-beta.10 < 0.2.0-rc.1 < 0.2.0 < 0.2.1-beta.1`),
 * and build metadata (`+abc`) does not count.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The dot-separated identifiers after the hyphen; empty for a release. */
    val preRelease: List<String> = emptyList(),
) : Comparable<AppVersion> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        // A release is later than any of its pre-releases.
        if (isPreRelease != other.isPreRelease) return if (isPreRelease) -1 else 1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            compareIdentifiers(preRelease[i], other.preRelease[i]).let { if (it != 0) return it }
        }
        // With the same start, the one that says more is later: beta < beta.1.
        return preRelease.size.compareTo(other.preRelease.size)
    }

    /** The name with nothing around it: `0.2.0`, `0.2.0-beta.1`. */
    override fun toString(): String =
        "$major.$minor.$patch" + if (isPreRelease) preRelease.joinToString(".", prefix = "-") else ""

    companion object {
        private val name = Regex(
            """v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?""",
        )

        /** The version [text] names, or null when it is not one (`nightly`, `latest`). */
        fun parse(text: String): AppVersion? {
            val (major, minor, patch, pre) = name.matchEntire(text.trim())?.destructured ?: return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), if (pre.isEmpty()) emptyList() else pre.split('.'))
        }

        /** Numbers by value and before words; words by their characters. */
        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumber = a.all { it in '0'..'9' }
            val bNumber = b.all { it in '0'..'9' }
            return when {
                aNumber && bNumber -> {
                    // By length first, so a number too long for a Long still compares right.
                    val x = a.trimStart('0')
                    val y = b.trimStart('0')
                    if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
                }
                aNumber -> -1
                bNumber -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/**
 * Whether [latest] is a later version than [current]: the updater's one
 * question. A [latest] that is not a version is never an update, and a
 * [current] that is not one is older than any.
 */
fun isVersionNewer(current: String, latest: String): Boolean {
    val offered = AppVersion.parse(latest) ?: return false
    val installed = AppVersion.parse(current) ?: return true
    return offered > installed
}

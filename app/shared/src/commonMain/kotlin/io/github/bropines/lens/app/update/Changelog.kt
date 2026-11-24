package io.github.bropines.lens.app.update

/**
 * Reader for the bundled `CHANGELOG.md` (Keep a Changelog format) and its
 * Russian twin `CHANGELOG_RU.md`, which "What's new" shows in a Russian
 * interface.
 *
 * Both files are copied from the repository root at build time (see
 * `bundleChangelog` in app/shared/build.gradle.kts), so the app always ships
 * the changelog of the commit it was built from. Only the structure the app
 * needs is parsed: `## [version] - date` sections, `### Group` sub-headings
 * and `- bullet` items (nested bullets are indented by two spaces per level).
 * The Russian file has the same structure, so the same parser reads it;
 * English decides which section is shown, and a version the Russian file
 * lacks is shown in English.
 */
object Changelog {
    const val FILE = "files/CHANGELOG.md"
    const val FILE_RU = "files/CHANGELOG_RU.md"
    const val FULL_URL = "${UpdateCheck.REPOSITORY}/blob/main/CHANGELOG.md"

    /** [level] is the bullet's nesting, or [PARAGRAPH] for a line that is not a bullet. */
    data class Item(val text: String, val level: Int)
    const val PARAGRAPH = -1
    data class Group(val title: String, val items: List<Item>)
    data class Section(val version: String, val date: String, val groups: List<Group>) {
        val isUnreleased: Boolean get() = version.equals("unreleased", ignoreCase = true)
        val hasText: Boolean get() = groups.any { it.items.isNotEmpty() }
    }

    private val sectionHeading = Regex("""^##\s+\[([^\]]+)\](?:\s*-\s*(.*))?\s*$""")
    private val groupHeading = Regex("""^#{1,6}\s+(.+?)\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val image = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)\]\([^)]*\)""")

    /**
     * The section of [english] shown in a build named [versionName], or the
     * section of [translated] with the same version, when it has text.
     *
     * A release shows its own section, or the newest released one when it has
     * none; never `[Unreleased]`. A pre-release (`0.2.0-beta.1`) has no
     * section: betas go out while their changes are still listed under
     * `[Unreleased]`, so it shows that one, under its own name.
     */
    fun pick(english: List<Section>, translated: List<Section>, versionName: String): Section? {
        val version = AppVersion.parse(versionName)
        val coming = if (version?.isPreRelease == true) english.firstOrNull { it.isUnreleased && it.hasText } else null
        val shown = coming
            ?: english.firstOrNull { !it.isUnreleased && AppVersion.parse(it.version) == version }
            ?: english.firstOrNull { !it.isUnreleased }
            ?: return null
        val own = translated.firstOrNull { it.version.equals(shown.version, ignoreCase = true) && it.hasText } ?: shown
        return if (coming != null) own.copy(version = version.toString()) else own
    }

    /**
     * The running build's version as the "already seen" key: `0.2.0` for
     * `v0.2.0` and `0.2.0+5`, so decoration never shows the dialog again.
     * A beta keeps its number, so each beta shows what is new once, and the
     * release after them once more.
     */
    fun seenKey(versionName: String): String = AppVersion.parse(versionName)?.toString() ?: versionName

    /** All sections of a changelog file, in file order (newest first). Text before the first section is skipped. */
    fun parse(markdown: String): List<Section> {
        val sections = mutableListOf<Section>()
        var version: String? = null
        var date = ""
        var body = mutableListOf<String>()

        fun flush() {
            val v = version ?: return
            sections += Section(v, date, groups(body))
        }

        for (raw in markdown.lineSequence()) {
            val section = sectionHeading.matchEntire(raw.trimEnd())
            if (section != null) {
                flush()
                version = section.groupValues[1].trim()
                date = section.groupValues[2].trim()
                body = mutableListOf()
            } else if (version != null) {
                body += raw
            }
        }
        flush()
        return sections
    }

    /**
     * Headings and bullets of a piece of Markdown: one changelog section, or a
     * release's notes from GitHub. Any heading starts a group; pictures are
     * dropped and links keep their text, since this is shown as plain text.
     */
    fun groups(markdown: String): List<Group> = groups(markdown.lines())

    private fun groups(lines: List<String>): List<Group> {
        val groups = mutableListOf<MutableGroup>()
        var group: MutableGroup? = null
        for (raw in lines) {
            val line = raw.trimEnd().replace(image, "").replace(link) { it.groupValues[1] }
            if (line.isBlank()) continue

            val heading = groupHeading.matchEntire(line)
            if (heading != null) {
                val title = heading.groupValues[1]
                // Keep a Changelog has each group once, but a hand-edited file can repeat one; merge them.
                group = groups.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: MutableGroup(title).also { groups += it }
                continue
            }

            val g = group ?: MutableGroup("").also { groups += it; group = it }
            val item = bullet.matchEntire(line)
            val last = g.items.lastOrNull()
            when {
                item != null -> g.items += Item(item.groupValues[2].trim(), item.groupValues[1].replace("\t", "  ").length / 2)
                // A wrapped line continues the bullet before it.
                last != null && raw.startsWith(" ") -> g.items[g.items.lastIndex] = last.copy(text = last.text + " " + line.trim())
                else -> g.items += Item(line.trim(), PARAGRAPH)
            }
        }
        return groups.filter { it.items.isNotEmpty() }.map { Group(it.title, it.items.toList()) }
    }

    private class MutableGroup(val title: String) {
        val items = mutableListOf<Item>()
    }
}

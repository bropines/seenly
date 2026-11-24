package io.github.bropines.lens.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangelogTest {
    private val english = Changelog.parse(
        """
        # Changelog

        All notable changes are documented here.

        ## [Unreleased]

        ### Added

        - A thing that is coming.

        ## [0.2.0] - 2026-12-01

        ### Added

        - In-app updates with a **beta** channel.
          A wrapped line.
          - A nested point with `code`.
        - On-device translation.

        ### Fixed

        - A crash.

        ### Added

        - A late addition.

        ## [0.1.0] - 2026-11-24

        - First release.
        """.trimIndent(),
    )

    private val russian = Changelog.parse(
        """
        ## [Unreleased]

        ## [0.2.0] - 2026-12-01

        ### Добавлено

        - Обновления из приложения.

        ## [0.1.0] - 2026-11-24
        """.trimIndent(),
    )

    @Test
    fun readsSectionsGroupsAndBullets() {
        assertEquals(listOf("Unreleased", "0.2.0", "0.1.0"), english.map { it.version })
        val section = english[1]
        assertEquals("2026-12-01", section.date)
        // A repeated group is merged into the first.
        assertEquals(listOf("Added", "Fixed"), section.groups.map { it.title })
        assertEquals(
            listOf(
                Changelog.Item("In-app updates with a **beta** channel. A wrapped line.", 0),
                Changelog.Item("A nested point with `code`.", 1),
                Changelog.Item("On-device translation.", 0),
                Changelog.Item("A late addition.", 0),
            ),
            section.groups[0].items,
        )
        // Bullets before any group heading still belong to the section.
        assertEquals(listOf(Changelog.Group("", listOf(Changelog.Item("First release.", 0)))), english[2].groups)
        assertTrue(Changelog.parse("no sections here").isEmpty())
    }

    @Test
    fun aReleaseShowsItsOwnSection() {
        assertEquals("0.2.0", Changelog.pick(english, emptyList(), "0.2.0")?.version)
        assertEquals("0.1.0", Changelog.pick(english, emptyList(), "v0.1.0")?.version)
        // A version the file does not list shows the newest released one, never Unreleased.
        assertEquals("0.2.0", Changelog.pick(english, emptyList(), "0.2.1")?.version)
        assertNull(Changelog.pick(emptyList(), russian, "0.2.0"))
    }

    @Test
    fun aBetaShowsWhatIsUnreleasedUnderItsOwnName() {
        val shown = Changelog.pick(english, emptyList(), "0.3.0-beta.1")
        assertEquals("0.3.0-beta.1", shown?.version)
        assertEquals("A thing that is coming.", shown?.groups?.single()?.items?.single()?.text)
        // With nothing listed there yet, the newest release.
        val empty = english.map { if (it.isUnreleased) it.copy(groups = emptyList()) else it }
        assertEquals("0.2.0", Changelog.pick(empty, emptyList(), "0.3.0-beta.1")?.version)
    }

    @Test
    fun theTranslationIsShownWhereItHasText() {
        assertEquals("Добавлено", Changelog.pick(english, russian, "0.2.0")?.groups?.single()?.title)
        // 0.1.0 has no Russian text, so English stands in.
        assertEquals("First release.", Changelog.pick(english, russian, "0.1.0")?.groups?.single()?.items?.single()?.text)
    }

    @Test
    fun seenKeyIgnoresDecoration() {
        assertEquals("0.2.0", Changelog.seenKey("v0.2.0"))
        assertEquals("0.2.0", Changelog.seenKey("0.2.0+7"))
        assertEquals("0.2.0-beta.2", Changelog.seenKey("0.2.0-beta.2"))
        assertEquals("custom", Changelog.seenKey("custom"))
    }

    @Test
    fun readsReleaseNotesAsPlainText() {
        val groups = Changelog.groups(
            """
            ## Highlights

            ![Screenshot](https://example.test/a.png)
            Updates now install from [the app](https://example.test) itself.

            ### Added

            - One.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Changelog.Group("Highlights", listOf(Changelog.Item("Updates now install from the app itself.", Changelog.PARAGRAPH))),
                Changelog.Group("Added", listOf(Changelog.Item("One.", 0))),
            ),
            groups,
        )
    }
}

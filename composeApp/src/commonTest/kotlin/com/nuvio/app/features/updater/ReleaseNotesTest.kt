package com.nuvio.app.features.updater

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.*

class ReleaseNotesTest {
    @Test fun legacyHistoryKeepsEveryVersionAndBullet() {
        val versions = parseReleaseNotes("""
            Starlit 1.0.22 adds bulk removal.

            - Select downloads.
              Keep unrelated files.

            Nuvio Native 1.0.14 introduces an installer.

            - Earlier change.
        """.trimIndent(), "Release", "v1.0.22")
        assertEquals(listOf("1.0.22", "1.0.14"), versions.map { it.version })
        assertEquals("Starlit 1.0.22", versions.first().title)
        assertEquals("Adds bulk removal.", versions.first().blocks.first().text)
        assertEquals("Select downloads.\nKeep unrelated files.", versions.first().blocks.last().text)
        assertEquals(ReleaseNotesBlock.Kind.Bullet, versions.last().blocks.last().kind)
    }

    @Test fun markdownSectionsAndUnknownTextRemainReadable() {
        val notes = """
            Release preamble
            # v2.0.0
            ## Features
            - **Better** `playback` and [details](https://example.test/details)
            <script>visible text only</script>
            | unknown | table |
            ```kotlin
            Starlit 9.0.0 is code, not a release
            ```
            ## v1.0.0
        """.trimIndent()
        val versions = parseReleaseNotes(notes, "Preview", "preview")
        assertEquals(listOf("preview", "2.0.0", "1.0.0"), versions.map { it.version })
        assertEquals("Release preamble", versions.first().blocks.single().text)
        assertEquals("Features", versions[1].blocks.first().text)
        assertTrue(versions[1].blocks.any { it.text.contains("<script>visible text only</script>") })
        assertTrue(versions[1].blocks.any { it.text.contains("| unknown | table |") })
        assertEquals(ReleaseNotesBlock.Kind.Code, versions[1].blocks.last().kind)
        assertTrue(versions.last().blocks.isEmpty())
    }

    @Test fun plainDebugAndEmptyReleaseBodiesHaveDeterministicFallbacks() {
        assertTrue(parseReleaseNotes(" \n", "Preview").isEmpty())
        val fallback = parseReleaseNotes("Test update\nNo versions here.", "Debug release", "debug")
        assertEquals("Debug release", fallback.single().title)
        assertEquals("debug", fallback.single().version)
        assertEquals("Test update\nNo versions here.", fallback.single().blocks.single().text)
    }

    @Test fun generatedGithubTailBelongsToCurrentReleaseInsteadOfOldestHistory() {
        val notes = """
            Starlit 1.0.22 adds bulk removal.
            - Latest change.
            Nuvio Native 1.0.11 improves browsing.
            - Oldest change.
            The Windows installer has checksums.

            <!-- Release notes generated using configuration in .github/release.yml at v1.0.22 -->

            ## What's Changed
            ### Features
            - New feature from a merged PR.
            **Full Changelog**: https://github.com/Beelzebub2/starlit/compare/v1.0.21...v1.0.22
        """.trimIndent()
        val releases = parseReleaseNotes(notes, "Starlit 1.0.22", "v1.0.22")
        assertEquals(2, releases.size)
        assertTrue(releases.first().blocks.any { it.text == "Features" })
        assertTrue(releases.first().blocks.last().text.contains("**Full Changelog**"))
        assertFalse(releases.last().blocks.any { it.text.contains("Full Changelog") })
        assertTrue(releases.last().blocks.last().text.contains("Windows installer"))
        assertFalse(releases.flatMap { it.blocks }.any { it.text.contains("<!--") })
        assertTrue(parseReleaseNotes("<!-- unrelated comment -->", "Release").single().blocks.single().text.contains("<!--"))
        val actualReleaseShape = notes.replace("## What's Changed\n### Features\n- New feature from a merged PR.\n", "")
        val actual = parseReleaseNotes(actualReleaseShape, "Starlit 1.0.22", "v1.0.22")
        assertTrue(actual.first().blocks.last().text.contains("**Full Changelog**"))
        assertFalse(actual.last().blocks.any { it.text.contains("Full Changelog") })
    }

    @Test fun inlineFormattingEnablesOnlyExplicitWebLinksAndNeverLoadsImages() {
        val formatted = releaseNotesAnnotatedText(
            "**Bold** `code` [safe](https://example.test/page) [bad](file:///secret) ![image](https://example.test/image.png)", Color.Blue,
        )
        assertEquals("Bold code safe bad ![image](https://example.test/image.png)", formatted.text)
        assertEquals(listOf("https://example.test/page"), formatted.getLinkAnnotations(0, formatted.length).map {
            (it.item as LinkAnnotation.Url).url
        })
        listOf("javascript:alert(1)", "data:text/html,hello", "file:///tmp/foo", "https://name:secret@example.test", "https://example.test/\n").forEach {
            assertNull(safeReleaseNotesUrl(it), it)
        }
        assertEquals("https://github.com/owner/repo/releases/tag/v1", githubReleaseNotesUrl("https://github.com/owner/repo/releases/tag/v1"))
        assertNull(githubReleaseNotesUrl("https://example.test/releases/tag/v1"))
        assertNull(githubReleaseNotesUrl("https://github.com.evil.test/owner/repo/releases"))
        assertNull(githubReleaseNotesUrl("https://github.com/owner/repo/issues"))
    }
}

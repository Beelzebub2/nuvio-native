package com.nuvio.app.features.player

import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSubtitleFilePickerTest {
    private fun withTemporaryFile(suffix: String, block: (File) -> Unit) {
        val file = Files.createTempFile("starlit-subtitle-test-", suffix).toFile()
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }

    @Test
    fun validSubtitleFormatsUseLocalUrisAndKeepUnicodeNames() {
        listOf(".SRT", ".ass", ".SSA", ".vtt").forEach { extension ->
            withTemporaryFile(extension) { file ->
                file.writeText("1\n00:00:00,100 --> 00:00:01,000\nTest\n")
                val selection = assertIs<LocalSubtitleFileResult.Selected>(validateLocalSubtitleFile(file))
                assertEquals(file.canonicalFile, File(URI(selection.uri)))
                assertEquals(file.name, selection.displayName)
                assertTrue(selection.uri.startsWith("file:"))
            }
        }
    }

    @Test
    fun selectedFileUriPreservesUnicodeNamesWithoutSavingTheLocalPath() {
        val directory = Files.createTempDirectory("starlit-subtitle-unicode-")
        val file = directory.resolve("legendas-ação.srt").toFile()
        try {
            file.writeText("1\n00:00:00,100 --> 00:00:01,000\nOlá\n")
            val result = assertIs<LocalSubtitleFileResult.Selected>(validateLocalSubtitleFile(file))
            assertEquals("legendas-ação.srt", result.displayName)
            assertEquals(file.canonicalFile, File(URI(result.uri)))
        } finally {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsUnsupportedAndEmptyFiles() {
        withTemporaryFile(".txt") { file ->
            file.writeText("hello")
            val rejection = assertIs<LocalSubtitleFileResult.Rejected>(validateLocalSubtitleFile(file))
            assertEquals(LocalSubtitleFileError.UNSUPPORTED_FORMAT, rejection.reason)
        }
        withTemporaryFile(".srt") { file ->
            val rejection = assertIs<LocalSubtitleFileResult.Rejected>(validateLocalSubtitleFile(file))
            assertEquals(LocalSubtitleFileError.UNREADABLE, rejection.reason)
        }
    }

    @Test
    fun rejectsMissingFiles() {
        withTemporaryFile(".srt") { file ->
            Files.delete(file.toPath())
            val rejection = assertIs<LocalSubtitleFileResult.Rejected>(validateLocalSubtitleFile(file))
            assertEquals(LocalSubtitleFileError.UNREADABLE, rejection.reason)
        }
    }

    @Test
    fun refusesOversizedSubtitleFilesBeforeGivingUriToPlayer() {
        withTemporaryFile(".srt") { file ->
            RandomAccessFile(file, "rw").use { it.setLength(MAX_LOCAL_SUBTITLE_BYTES + 1) }
            val rejection = assertIs<LocalSubtitleFileResult.Rejected>(validateLocalSubtitleFile(file))
            assertEquals(LocalSubtitleFileError.TOO_LARGE, rejection.reason)
        }
    }
}

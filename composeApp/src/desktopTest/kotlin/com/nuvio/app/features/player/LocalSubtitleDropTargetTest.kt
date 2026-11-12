package com.nuvio.app.features.player

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LocalSubtitleDropTargetTest {
    private fun fileListTransfer(files: List<*>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            require(isDataFlavorSupported(flavor))
            return files
        }
    }

    @Test
    fun acceptsExactlyOneReadableSubtitleUsingExistingFileChecks() {
        val file = Files.createTempFile("starlit-drop-", ".ass").toFile()
        try {
            file.writeText("[Script Info]\nTitle: Example\n")
            val selected = assertIs<LocalSubtitleFileResult.Selected>(
                validateDroppedLocalSubtitle(fileListTransfer(listOf(file))),
            )
            assertEquals(file.canonicalFile.toURI().toString(), selected.uri)
            assertEquals(file.name, selected.displayName)
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }

    @Test
    fun rejectsMultipleFilesWithoutChoosingOneSilently() {
        val result = assertIs<LocalSubtitleFileResult.Rejected>(
            validateDroppedLocalSubtitle(fileListTransfer(listOf(File("one.ass"), File("two.srt")))),
        )
        assertEquals(LocalSubtitleFileError.MULTIPLE_FILES, result.reason)
    }

    @Test
    fun rejectsMalformedFileListsAndUnsupportedFormats() {
        val malformed = assertIs<LocalSubtitleFileResult.Rejected>(
            validateDroppedLocalSubtitle(fileListTransfer(listOf("not-a-file"))),
        )
        assertEquals(LocalSubtitleFileError.UNREADABLE, malformed.reason)

        val txt = Files.createTempFile("starlit-drop-", ".txt").toFile()
        try {
            txt.writeText("not a subtitle")
            val unsupported = assertIs<LocalSubtitleFileResult.Rejected>(
                validateDroppedLocalSubtitle(fileListTransfer(listOf(txt))),
            )
            assertEquals(LocalSubtitleFileError.UNSUPPORTED_FORMAT, unsupported.reason)
        } finally {
            Files.deleteIfExists(txt.toPath())
        }
    }

    @Test
    fun ignoresTextAndRemoteUrlDrops() {
        assertNull(validateDroppedLocalSubtitle(StringSelection("https://example.invalid/subtitles.ass")))
        assertNull(validateDroppedLocalSubtitle(StringSelection("C:\\Temp\\subtitles.ass")))
    }
}

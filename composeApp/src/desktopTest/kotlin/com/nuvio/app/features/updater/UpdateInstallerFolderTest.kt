package com.nuvio.app.features.updater

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInstallerFolderTest {

    @Test
    fun `installers left by finished updates are removed, including the old roaming folder`() {
        val root = Files.createTempDirectory("update-installers").toFile()
        try {
            val current = File(root, "Cache/updates").apply { mkdirs() }
            val legacy = File(root, "updates").apply { mkdirs() }
            File(current, "Starlit-Windows-x64-1.0.18.exe").writeText("setup")
            File(current, "Starlit-Windows-x64-1.0.18.exe.part").writeText("partial")
            File(legacy, "Starlit-Windows-x64-1.0.17.exe").writeText("setup")

            assertTrue(UpdateInstallerFolder(current, listOf(legacy)).removeLeftovers())

            assertTrue(current.listFiles().isNullOrEmpty())
            assertFalse(legacy.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a download started in this session is never cleaned up as a leftover`() {
        val root = Files.createTempDirectory("update-installers").toFile()
        try {
            val folder = UpdateInstallerFolder(File(root, "updates"))
            val downloaded = File(folder.claimForDownload(), "Starlit-Windows-x64-1.0.19.exe").apply { writeText("setup") }

            assertTrue(folder.removeLeftovers())

            assertEquals("setup", downloaded.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a new download replaces the previous installer`() {
        val root = Files.createTempDirectory("update-installers").toFile()
        try {
            val current = File(root, "updates").apply { mkdirs() }
            val previous = File(current, "Starlit-Windows-x64-1.0.17.exe").apply { writeText("setup") }

            UpdateInstallerFolder(current).claimForDownload()

            assertFalse(previous.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an installer still held open by setup is reported for a later retry`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val root = Files.createTempDirectory("update-installers").toFile()
        try {
            val current = File(root, "updates").apply { mkdirs() }
            val running = File(current, "Starlit-Windows-x64-1.0.18.exe").apply { writeText("setup") }
            val folder = UpdateInstallerFolder(current)

            // Windows refuses to delete a file another handle has open, like a running setup.exe.
            FileInputStream(running).use { assertFalse(folder.removeLeftovers()) }

            assertTrue(folder.removeLeftovers())
            assertFalse(running.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

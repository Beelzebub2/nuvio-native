package com.nuvio.app.core.ui

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Label
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileDialogsTest {
    @Test fun backupExtensionUsesTheFinalPathWithoutChangingExistingJsonNames() {
        assertEquals(File("folder/report.txt.json"), jsonBackupFile(File("folder/report.txt")))
        assertEquals(File("folder/report.json"), jsonBackupFile(File("folder/report")))
        assertEquals(File("folder/report.JSON"), jsonBackupFile(File("folder/report.JSON")))
    }

    /** Opt-in real OS dialog exercise; never reads/writes an installed app's data. */
    @Test fun nativeDialogSmoke() = runBlocking {
        val mode = System.getenv("STARLIT_FILE_DIALOG_SMOKE")
        assumeTrue(mode != null && DesktopHostOs.current == DesktopHostOs.WINDOWS)
        val root = File(System.getProperty("nuvio.dataDir")).parentFile.resolve("dialog-fixtures").apply { mkdirs() }
        val unicodeFolder = root.resolve("Vídeos 日本語").apply { mkdirs() }
        val backup = root.resolve("addons 日本語.json").apply { writeText("{\"addons\":[]}") }
        val existing = root.resolve("already.json").apply { writeText("do not replace") }
        val frame = Frame("Starlit picker test owner")
        EventQueue.invokeAndWait {
            frame.add(Label("Isolated dialog test — no installed app data is used."))
            frame.setSize(540, 130)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            frame.toFront()
        }
        try {
            val title = "Starlit picker smoke — $mode"
            when (mode) {
                "folder" -> assertEquals(unicodeFolder, DesktopFileDialogs.folder(title, root.path))
                "cancel" -> assertNull(DesktopFileDialogs.folder(title, root.path))
                "open" -> assertEquals(backup, DesktopFileDialogs.json(title, save = false, overwrite = "Replace backup?"))
                "overwrite-cancel" -> assertNull(DesktopFileDialogs.json(title, save = true, overwrite = "Replace backup?"))
                "save" -> assertEquals(root.resolve("new backup.json"), DesktopFileDialogs.json(title, save = true, overwrite = "Replace backup?"))
                "scope-cancel" -> {
                    NativePlayerBridge.ensureLoaded()
                    val cancelled = AtomicBoolean(false)
                    var failure: Throwable? = null
                    var result: String? = "not returned"
                    val thread = Thread {
                        try { result = WindowsFileDialog.show(0, 0, title, root.path, cancelled) }
                        catch (error: Throwable) { failure = error }
                    }.apply { isDaemon = true; start() }
                    Thread.sleep(2000)
                    cancelled.set(true)
                    thread.join(5000)
                    assertFalse(thread.isAlive, "Cancelling the scope must close the native dialog")
                    assertNull(failure)
                    assertNull(result)
                }
                else -> error("Unknown picker smoke mode: $mode")
            }
            assertEquals("do not replace", existing.readText())
            assertEquals("{\"addons\":[]}", backup.readText())
            assertTrue(unicodeFolder.isDirectory)
        } finally { EventQueue.invokeAndWait { frame.dispose() } }
    }
}

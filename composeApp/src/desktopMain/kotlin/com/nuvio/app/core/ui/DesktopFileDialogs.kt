package com.nuvio.app.core.ui

import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared by download folders and add-on backup import/export. */
internal object DesktopFileDialogs {
    suspend fun folder(title: String, current: String): File? = choose(title, Mode.Folder, current)

    suspend fun json(title: String, save: Boolean, overwrite: String): File? =
        choose(title, if (save) Mode.SaveJson else Mode.OpenJson, overwrite = overwrite)

    private enum class Mode { Folder, OpenJson, SaveJson }

    private suspend fun choose(title: String, mode: Mode, current: String = "", overwrite: String = ""): File? =
        suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            continuation.invokeOnCancellation { cancelled.set(true) }
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                try {
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                        val hwnd = owner?.takeIf { it.isDisplayable }?.let {
                            AwtNativeViewResolver.resolveNativeViewPointer(it)
                        } ?: 0L
                        // A fresh thread gives the Common Item Dialog its required STA apartment.
                        Thread({
                            continuation.resumeWith(runCatching {
                                NativePlayerBridge.ensureLoaded()
                                val file = WindowsFileDialog.show(hwnd, mode.ordinal, title, current, cancelled)?.let(::File)
                                check(mode != Mode.SaveJson || file == null || file.extension.equals("json", true)) {
                                    "The system dialog returned an unexpected backup file type"
                                }
                                file
                            })
                        }, "starlit-file-dialog").apply { isDaemon = true; start() }
                    } else {
                        val file = if (mode == Mode.Folder && DesktopHostOs.current != DesktopHostOs.MACOS) {
                            systemFolderDialog(owner, title, current, cancelled)
                        } else {
                            awtDialog(owner, title, mode, current, overwrite, cancelled)
                        }
                        continuation.resume(file)
                    }
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
        }

    private fun awtDialog(owner: Window?, title: String, mode: Mode, current: String,
                          overwrite: String, cancelled: AtomicBoolean): File? {
        val dialog = when (owner) {
            is Dialog -> FileDialog(owner, title)
            else -> FileDialog(owner as? Frame, title)
        }
        val directoryProperty = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(directoryProperty)
        try {
            if (DesktopHostOs.current == DesktopHostOs.MACOS) System.setProperty(directoryProperty, (mode == Mode.Folder).toString())
            dialog.mode = if (mode == Mode.SaveJson) FileDialog.SAVE else FileDialog.LOAD
            dialog.directory = current.takeIf(String::isNotBlank)
            if (mode != Mode.Folder) dialog.setFilenameFilter { _, name -> name.endsWith(".json", true) }
            if (mode == Mode.SaveJson) dialog.file = "starlit-addons.json"
            // AWT's modal secondary loop processes this timer on the EDT as well.
            val timer = javax.swing.Timer(100) { if (cancelled.get()) dialog.dispose() }
            timer.start()
            try { if (!cancelled.get()) dialog.isVisible = true } finally { timer.stop() }
            if (cancelled.get()) return null
            val name = dialog.file ?: return null
            val selected = File(dialog.directory, name)
            if (mode != Mode.SaveJson) return selected
            val finalFile = jsonBackupFile(selected)
            // Native save already confirmed selected. If an extension was appended, confirm the FINAL target too.
            if (finalFile != selected && finalFile.exists() &&
                JOptionPane.showConfirmDialog(owner, overwrite, title, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
            return finalFile
        } finally {
            dialog.dispose()
            if (DesktopHostOs.current == DesktopHostOs.MACOS) {
                if (previous == null) System.clearProperty(directoryProperty) else System.setProperty(directoryProperty, previous)
            }
        }
    }

    // AWT has no directory-selection mode on Linux. Keep that fallback in the desktop's system theme.
    private fun systemFolderDialog(owner: Window?, title: String, current: String, cancelled: AtomicBoolean): File? {
        val previous = UIManager.getLookAndFeel()
        try {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser(current).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
            }
            val timer = javax.swing.Timer(100) { if (cancelled.get()) chooser.cancelSelection() }
            timer.start()
            return try {
                if (!cancelled.get() && chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            } finally { timer.stop() }
        } finally {
            if (UIManager.getLookAndFeel() !== previous) runCatching { UIManager.setLookAndFeel(previous) }
        }
    }
}

internal fun jsonBackupFile(file: File): File =
    if (file.name.endsWith(".json", true)) file else File(file.path + ".json")

internal object WindowsFileDialog {
    external fun show(owner: Long, mode: Int, title: String, current: String, cancelled: AtomicBoolean): String?
}

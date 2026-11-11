package com.nuvio.app.features.player

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer
import kotlin.coroutines.resume

internal const val MAX_LOCAL_SUBTITLE_BYTES = 16L * 1024L * 1024L
private val supportedSubtitleExtensions = setOf("srt", "ass", "ssa", "vtt")

internal fun validateLocalSubtitleFile(candidate: File): LocalSubtitleFileResult {
    val file = runCatching { candidate.canonicalFile }.getOrNull()
        ?: return LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE)
    if (!file.isFile || !file.canRead()) {
        return LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE)
    }
    if (file.extension.lowercase() !in supportedSubtitleExtensions) {
        return LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNSUPPORTED_FORMAT)
    }
    val size = file.length()
    if (size <= 0) return LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.UNREADABLE)
    if (size > MAX_LOCAL_SUBTITLE_BYTES) {
        return LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.TOO_LARGE)
    }
    return LocalSubtitleFileResult.Selected(uri = file.toURI().toString(), displayName = file.name)
}

internal actual suspend fun chooseLocalSubtitleFile(title: String): LocalSubtitleFileResult =
    suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        continuation.invokeOnCancellation { cancelled.set(true) }
        EventQueue.invokeLater {
            if (!continuation.isActive) return@invokeLater
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            val dialog = when (owner) {
                is Dialog -> FileDialog(owner, title)
                else -> FileDialog(owner as? Frame, title)
            }
            try {
                dialog.mode = FileDialog.LOAD
                dialog.setFilenameFilter { _, name ->
                    name.substringAfterLast('.', "").lowercase() in supportedSubtitleExtensions
                }
                // AWT's modal loop keeps dispatching the EDT timer while the picker is shown.
                val timer = Timer(100) { if (cancelled.get()) dialog.dispose() }
                timer.start()
                try {
                    if (!cancelled.get()) dialog.isVisible = true
                } finally {
                    timer.stop()
                }
                val selected = dialog.file?.let { name -> File(dialog.directory, name) }
                val result = if (cancelled.get() || selected == null) {
                    LocalSubtitleFileResult.Cancelled
                } else {
                    validateLocalSubtitleFile(selected)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resume(LocalSubtitleFileResult.Rejected(LocalSubtitleFileError.PICKER_FAILED))
                }
            } finally {
                dialog.dispose()
            }
        }
    }

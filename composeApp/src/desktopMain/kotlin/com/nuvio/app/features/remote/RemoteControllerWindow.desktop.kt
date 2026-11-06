package com.nuvio.app.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.awt.SwingDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import java.awt.Dialog.ModalityType
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.WindowConstants

private val RemoteWindowBackground = java.awt.Color(0x0D, 0x0D, 0x0D)

@Composable
internal actual fun RemoteControllerWindow(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    SwingDialog(
        onPreviewKeyEvent = { event ->
            (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) close() }
        },
        create = {
            // Owned by the main window (no extra taskbar entry) but modeless, so the
            // player and the rest of Starlit keep working while the remote is open.
            val owner = remoteOwnerWindow()
            ComposeDialog(owner, ModalityType.MODELESS).apply {
                this.title = title
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = close()
                })
                background = RemoteWindowBackground
                minimumSize = Dimension(560, 520)
                size = Dimension(900, 680)
                isResizable = true
                owner?.iconImages?.let { iconImages = it }
                setLocationRelativeTo(owner)
            }
        },
        dispose = ComposeDialog::dispose,
        update = { it.title = title },
    ) {
        LaunchedEffect(window) {
            // The dark caption needs the native peer, which exists after the first frame.
            withFrameNanos { }
            applyNativeDesktopWindowChrome(window)
            window.toFront()
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
    }
}

private fun remoteOwnerWindow(): Window? =
    KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        ?: Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible && it.owner == null }

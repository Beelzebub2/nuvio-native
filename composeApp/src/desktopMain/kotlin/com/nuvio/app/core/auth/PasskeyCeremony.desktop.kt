package com.nuvio.app.core.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.Native
import com.sun.jna.Platform
import java.awt.KeyboardFocusManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

// Windows is the WebAuthn client. Its hybrid transport owns the QR/proximity
// exchange with the phone; no browser, loopback listener or private key export.
@Composable
actual fun rememberPasskeyCeremony(): PasskeyCeremony? = remember {
    if (!Platform.isWindows()) null else runCatching {
        val api = Native.load(File(System.getenv("SystemRoot"), "System32/webauthn.dll").absolutePath,
            WindowsWebAuthn::class.java)
        // API v6 introduced hybrid transport. Older Windows must not offer a broken phone picker.
        if (api.WebAuthNGetApiVersionNumber() < 6) null else WindowsPasskeyCeremony(api) {
            withContext(Dispatchers.Swing) {
                val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                check(window != null && window.isShowing) { "Return to Starlit and try again" }
                Native.getComponentPointer(window)
            }
        }
    }.getOrNull()
}

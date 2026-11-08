package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.loadSystemLibrary
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/**
 * Request the Windows display to turn back on following idle screen timeout.
 * Called only from a dedicated background thread after an authenticated
 * phone initiated playback, never from the socket or Compose UI thread.
 *
 * This is not Wake-on-LAN, does not resume a suspended PC, and cannot unlock
 * a locked Windows session or override a monitor's physical power switch.
 */
internal object WindowsRemoteDisplayWake {
    private interface User32 : StdCallLibrary {
        fun SendMessageTimeoutW(
            hwnd: Pointer, message: Int, wParam: Pointer, lParam: Pointer,
            flags: Int, timeoutMs: Int, result: Pointer?,
        ): Pointer?
    }

    private const val WM_SYSCOMMAND = 0x0112
    private const val SC_MONITORPOWER = 0xF170
    private const val SMTO_ABORTIFHUNG = 0x0002
    private const val TIMEOUT_MS = 500
    private val user32 by lazy { loadSystemLibrary("user32", User32::class.java) }

    fun powerOn(): Boolean = user32.SendMessageTimeoutW(
        Pointer.createConstant(0xffffL), WM_SYSCOMMAND,
        Pointer.createConstant(SC_MONITORPOWER.toLong()),
        Pointer.createConstant(-1L), SMTO_ABORTIFHUNG, TIMEOUT_MS, null,
    ) != null
}

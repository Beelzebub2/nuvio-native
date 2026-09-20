package com.nuvio.app.features.player.desktop

import java.awt.Window

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

internal fun applyNativeDesktopWindowChrome(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.applyWindowChrome(
            windowHwnd = hwnd,
            darkMode = true,
            captionColorRgb = NuvioWindowBackgroundRgb,
            borderColorRgb = NuvioWindowBackgroundRgb,
            textColorRgb = NuvioWindowTextRgb,
        )
    }
}

// AWT's own first-show activation attempt (java.desktop's AwtFrame::WmShowWindow) is routinely
// denied by Windows' focus-stealing prevention once enough startup work has happened before the
// window appears, leaving the window merely visible with its taskbar button flashing instead of
// active. Force it to the foreground for real.
internal fun forceDesktopWindowForeground(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.forceForegroundWindow(hwnd)
    }
}

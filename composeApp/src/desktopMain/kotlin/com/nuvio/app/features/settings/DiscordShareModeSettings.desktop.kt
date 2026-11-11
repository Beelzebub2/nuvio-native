package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DiscordShareModePlatform {
    private val store by lazy { DesktopStorage.store("starlit_discord_audio_share") }
    // New capture mode: deliberately do not inherit the old whole-screen
    // companion opt-in, which could result in two mixed audio sources.
    private const val key = "share_stereo_window_capture_enabled"
    private const val windowKey = "share_stereo_companion_window_visible"

    actual val isSupported: Boolean = System.getProperty("os.name", "")
        .startsWith("windows", ignoreCase = true) &&
        System.getProperty("os.arch", "").lowercase() in setOf("amd64", "x64", "x86_64")

    actual fun loadEnabled(): Boolean = store.getBoolean(key) ?: false

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(key, enabled)
    }

    actual fun loadShowWindow(): Boolean = store.getBoolean(windowKey) ?: false

    actual fun saveShowWindow(visible: Boolean) {
        store.putBoolean(windowKey, visible)
    }
}

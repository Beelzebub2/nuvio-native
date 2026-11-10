package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in stereo companion. The primary player's audio output is never modified. */
internal object DiscordShareModeSettings {
    val isSupported: Boolean get() = DiscordShareModePlatform.isSupported

    private val value = MutableStateFlow(
        if (DiscordShareModePlatform.isSupported) {
            // Never re-enable an old full-stereo helper on Entire Screen.
            // Hidden dialogue-only mode is opted into anew each session.
            runCatching { DiscordShareModePlatform.loadEnabled() && DiscordShareModePlatform.loadShowWindow() }
                .getOrDefault(false)
        } else {
            false
        },
    )
    val enabled = value.asStateFlow()
    private val showWindowState = MutableStateFlow(
        if (DiscordShareModePlatform.isSupported) {
            runCatching { DiscordShareModePlatform.loadShowWindow() }.getOrDefault(false)
        } else {
            false
        },
    )
    val showWindow = showWindowState.asStateFlow()
    private val unavailableState = MutableStateFlow(false)
    val unavailable = unavailableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        unavailableState.value = false
        if (enabled) {
            // Entire Screen is the default: a hidden helper contributes only
            // FC dialogue, never a second full-range stereo music mix.
            DiscordShareModePlatform.saveShowWindow(false)
            showWindowState.value = false
        }
        DiscordShareModePlatform.saveEnabled(enabled)
        value.value = enabled
    }

    fun setShowWindow(visible: Boolean) {
        if (!isSupported) return
        // Visible mode outputs full stereo for app-window-only capture;
        // hidden mode outputs only multichannel centre dialogue for fullscreen.
        DiscordShareModePlatform.saveShowWindow(visible)
        showWindowState.value = visible
    }

    fun markUnavailable() {
        if (!isSupported) return
        DiscordShareModePlatform.saveEnabled(false)
        value.value = false
        unavailableState.value = true
    }
}

internal expect object DiscordShareModePlatform {
    val isSupported: Boolean
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
    fun loadShowWindow(): Boolean
    fun saveShowWindow(visible: Boolean)
}

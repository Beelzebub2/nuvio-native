package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in for an experimental, Windows-only centre-channel audio supplement.
 * The preference does not redirect or change the main player's audio output.
 */
internal object WindowsDiscordDialogueRepository {
    val isSupported: Boolean
        get() = WindowsDiscordDialoguePlatform.isSupported

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _enabled.value = if (isSupported) WindowsDiscordDialoguePlatform.loadEnabled() ?: false else false
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        val accepted = enabled && isSupported
        if (_enabled.value == accepted) return
        _enabled.value = accepted
        WindowsDiscordDialoguePlatform.saveEnabled(accepted)
    }
}

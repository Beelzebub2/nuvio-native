package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Experimental PCM-only upmix. Never modify native multichannel sources. */
internal object SurroundUpmixSettings {
    val isSupported: Boolean get() = SurroundUpmixPlatform.isSupported

    private val enabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadEnabled() }.getOrDefault(false)
        else false,
    )
    val enabled = enabledState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        SurroundUpmixPlatform.saveEnabled(enabled)
        enabledState.value = enabled
    }
}

internal expect object SurroundUpmixPlatform {
    val isSupported: Boolean
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
}

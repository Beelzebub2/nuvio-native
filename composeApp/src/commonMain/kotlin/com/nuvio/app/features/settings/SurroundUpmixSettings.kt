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

    // Virtual 7.1 is a distinct, explicit mode. The two modes can never be
    // active together, so one playback session only installs one named filter.
    private val headsetEnabledState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadHeadsetEnabled() }.getOrDefault(false)
        else false,
    )
    val headsetEnabled = headsetEnabledState.asStateFlow()
    private val preferHeadsetState = MutableStateFlow(
        if (isSupported) runCatching { SurroundUpmixPlatform.loadPreferHeadset() }.getOrDefault(false)
        else false,
    )
    val preferHeadset = preferHeadsetState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveHeadsetEnabled(false)
            headsetEnabledState.value = false
            SurroundUpmixPlatform.savePreferHeadset(false)
            preferHeadsetState.value = false
        }
        SurroundUpmixPlatform.saveEnabled(enabled)
        enabledState.value = enabled
    }

    fun setHeadsetEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (enabled) {
            SurroundUpmixPlatform.saveEnabled(false)
            enabledState.value = false
            SurroundUpmixPlatform.savePreferHeadset(true)
            preferHeadsetState.value = true
        }
        SurroundUpmixPlatform.saveHeadsetEnabled(enabled)
        headsetEnabledState.value = enabled
    }

    /** In-player toggle remembers the last selected target when switched off. */
    fun setPlayerEnabled(enabled: Boolean) {
        if (!isSupported) return
        if (!enabled) {
            setEnabled(false)
            setHeadsetEnabled(false)
        } else if (preferHeadsetState.value) {
            setHeadsetEnabled(true)
        } else {
            setEnabled(true)
        }
    }
}

internal expect object SurroundUpmixPlatform {
    val isSupported: Boolean
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
    fun loadHeadsetEnabled(): Boolean
    fun saveHeadsetEnabled(enabled: Boolean)
    fun loadPreferHeadset(): Boolean
    fun savePreferHeadset(prefer: Boolean)
}

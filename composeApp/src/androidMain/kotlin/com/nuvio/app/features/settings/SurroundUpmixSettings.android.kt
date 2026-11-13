package com.nuvio.app.features.settings

internal actual object SurroundUpmixPlatform {
    actual val isSupported: Boolean = false
    actual fun loadEnabled(): Boolean = false
    actual fun saveEnabled(enabled: Boolean) = Unit
    actual fun loadHeadsetEnabled(): Boolean = false
    actual fun saveHeadsetEnabled(enabled: Boolean) = Unit
    actual fun loadPreferHeadset(): Boolean = false
    actual fun savePreferHeadset(prefer: Boolean) = Unit
}

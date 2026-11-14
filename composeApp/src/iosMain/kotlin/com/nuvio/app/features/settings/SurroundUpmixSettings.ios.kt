package com.nuvio.app.features.settings

internal actual object SurroundUpmixPlatform {
    actual val isSupported: Boolean = false
    actual fun loadEnabled(): Boolean = false
    actual fun saveEnabled(enabled: Boolean) = Unit
    actual fun loadHeadsetEnabled(): Boolean = false
    actual fun saveHeadsetEnabled(enabled: Boolean) = Unit
    actual fun loadHeadsetFivePointOneEnabled(): Boolean = false
    actual fun saveHeadsetFivePointOneEnabled(enabled: Boolean) = Unit
    actual fun loadPreferHeadset(): Boolean = false
    actual fun savePreferHeadset(prefer: Boolean) = Unit
    actual fun loadPreferHeadsetFivePointOne(): Boolean = false
    actual fun savePreferHeadsetFivePointOne(prefer: Boolean) = Unit
}

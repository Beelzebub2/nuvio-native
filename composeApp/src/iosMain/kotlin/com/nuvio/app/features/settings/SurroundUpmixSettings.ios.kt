package com.nuvio.app.features.settings

internal actual object SurroundUpmixPlatform {
    actual val isSupported: Boolean = false
    actual fun loadEnabled(): Boolean = false
    actual fun saveEnabled(enabled: Boolean) = Unit
}

package com.nuvio.app.features.settings

internal actual object WindowsDiscordDialoguePlatform {
    actual val isSupported: Boolean = false
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) = Unit
}

package com.nuvio.app.features.settings

internal actual object DiscordShareModePlatform {
    actual val isSupported: Boolean = false
    actual fun loadEnabled(): Boolean = false
    actual fun saveEnabled(enabled: Boolean) = Unit
    actual fun loadShowWindow(): Boolean = false
    actual fun saveShowWindow(visible: Boolean) = Unit
}

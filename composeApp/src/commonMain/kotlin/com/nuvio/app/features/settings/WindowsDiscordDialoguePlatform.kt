package com.nuvio.app.features.settings

internal expect object WindowsDiscordDialoguePlatform {
    val isSupported: Boolean
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}

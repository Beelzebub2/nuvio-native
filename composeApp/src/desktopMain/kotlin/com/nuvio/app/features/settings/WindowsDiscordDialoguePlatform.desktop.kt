package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.Locale

internal actual object WindowsDiscordDialoguePlatform {
    private const val enabledKey = "enabled"
    private val store by lazy { DesktopStorage.store("starlit_windows_discord_dialogue") }

    actual val isSupported: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows") &&
            runCatching { NativePlayerBridge.isDiscordDialogueEndpointAvailable() }.getOrDefault(false)

    actual fun loadEnabled(): Boolean? = store.getBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(enabledKey, enabled)
    }
}

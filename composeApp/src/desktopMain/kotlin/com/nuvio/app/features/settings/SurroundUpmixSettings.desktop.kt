package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SurroundUpmixPlatform {
    private val store by lazy { DesktopStorage.store("starlit_surround_upmix") }
    private const val key = "surround_upmix_windows_beta_enabled_v1"

    actual val isSupported: Boolean =
        System.getProperty("os.name", "").startsWith("windows", ignoreCase = true) &&
            System.getProperty("os.arch", "").lowercase() in setOf("amd64", "x64", "x86_64")

    actual fun loadEnabled(): Boolean = store.getBoolean(key) ?: false

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(key, enabled)
    }
}

package com.nuvio.app.core.storage

import com.nuvio.app.features.myanimelist.protectMalCredential

/** The public Windows build seals credentials to the current Windows user with DPAPI. */
internal object DesktopCredentialProtection {
    private const val PREFIX = "dpapi:v1:"
    private val windows = System.getProperty("os.name").startsWith("Windows")

    fun read(store: DesktopStorage.Store, key: String): String? {
        val saved = store.getString(key) ?: return null
        if (!windows) return saved // Other desktop OSes need a native secrets-service backend.
        if (saved.startsWith(PREFIX)) return runCatching {
            val plaintext = protectMalCredential(saved.removePrefix(PREFIX), decrypt = true)
            store.scrubProtectedBackup()
            plaintext
        }.getOrNull()
        if (saved.startsWith("dpapi:")) return null
        // Migrate older plaintext values immediately, without sending them to logs.
        store.putProtectedString(key, PREFIX + protectMalCredential(saved))
        return saved
    }

    fun write(store: DesktopStorage.Store, key: String, value: String?) {
        store.putProtectedString(key, if (value == null) null else if (windows)
            PREFIX + protectMalCredential(value) else value)
    }
}

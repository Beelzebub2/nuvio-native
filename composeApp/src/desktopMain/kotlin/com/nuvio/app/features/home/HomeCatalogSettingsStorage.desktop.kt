package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object HomeCatalogSettingsStorage {
    private val store = DesktopStorage.store("nuvio_home_catalog_settings")
    // The account reset clears DesktopStorage.rootDir, so keep the one-time
    // installation choice alongside that directory in an independent store.
    private val installationStore by lazy {
        DesktopStorage.Store(
            DesktopStorage.rootDir.resolveSibling("${DesktopStorage.rootDir.fileName}-installation.properties"),
        )
    }

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("home_catalog_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("home_catalog_settings"), payload)
    }

    actual fun loadBuiltInAiringChoice(): Boolean? =
        installationStore.getString("built_in_airing_first_run_choice")?.toBooleanStrictOrNull()

    actual fun saveBuiltInAiringChoice(enabled: Boolean) {
        installationStore.putString("built_in_airing_first_run_choice", enabled.toString())
    }
}

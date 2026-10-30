package com.nuvio.app.features.home

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object HomeCatalogSettingsStorage {
    private const val payloadKey = "catalog_settings_payload"
    private const val builtInAiringChoiceKey = "built_in_airing_first_run_choice"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadBuiltInAiringChoice(): Boolean? =
        NSUserDefaults.standardUserDefaults.stringForKey(builtInAiringChoiceKey)?.toBooleanStrictOrNull()

    actual fun saveBuiltInAiringChoice(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setObject(enabled.toString(), forKey = builtInAiringChoiceKey)
    }
}

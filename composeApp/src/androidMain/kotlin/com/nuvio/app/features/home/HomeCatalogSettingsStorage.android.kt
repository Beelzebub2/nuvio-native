package com.nuvio.app.features.home

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object HomeCatalogSettingsStorage {
    private const val preferencesName = "nuvio_home_catalog_settings"
    private const val installationPreferencesName = "nuvio_install_preferences"
    private const val payloadKey = "catalog_settings_payload"
    private const val builtInAiringChoiceKey = "built_in_airing_first_run_choice"

    private var preferences: SharedPreferences? = null
    private var installationPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        // The account-data cleaner clears preferencesName on sign-out.
        installationPreferences = context.getSharedPreferences(installationPreferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    actual fun loadBuiltInAiringChoice(): Boolean? =
        installationPreferences?.getString(builtInAiringChoiceKey, null)?.toBooleanStrictOrNull()

    actual fun saveBuiltInAiringChoice(enabled: Boolean) {
        installationPreferences?.edit()?.putString(builtInAiringChoiceKey, enabled.toString())?.apply()
    }
}

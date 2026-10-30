package com.nuvio.app.features.home

internal expect object HomeCatalogSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    /** Installation-wide first-run answer, shared by new profiles on this device. */
    fun loadBuiltInAiringChoice(): Boolean?
    fun saveBuiltInAiringChoice(enabled: Boolean)
}

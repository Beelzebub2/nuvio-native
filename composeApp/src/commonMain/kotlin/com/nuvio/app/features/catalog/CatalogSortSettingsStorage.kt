package com.nuvio.app.features.catalog

internal expect object CatalogSortSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

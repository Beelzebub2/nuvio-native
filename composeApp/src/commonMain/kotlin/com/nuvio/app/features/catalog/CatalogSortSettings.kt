package com.nuvio.app.features.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class CatalogSortSettingsUiState(
    val selections: Map<String, CatalogSortOption> = emptyMap(),
)

internal object CatalogSortSettingsRepository {
    private val _uiState = MutableStateFlow(CatalogSortSettingsUiState())
    val uiState: StateFlow<CatalogSortSettingsUiState> = _uiState.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) onProfileChanged()
    }

    fun onProfileChanged() {
        _uiState.value = decodeCatalogSortSettings(CatalogSortSettingsStorage.loadPayload())
        loaded = true
    }

    fun clearLocalState() {
        loaded = false
        _uiState.value = CatalogSortSettingsUiState()
    }

    fun setSort(target: CatalogTarget, selected: CatalogSortOption) {
        val key = catalogSortKey(target) ?: return
        ensureLoaded()
        val updated = updateCatalogSortSelections(_uiState.value.selections, key, selected)
        if (updated == _uiState.value.selections) return
        _uiState.value = CatalogSortSettingsUiState(updated)
        CatalogSortSettingsStorage.savePayload(encodeCatalogSortSettings(_uiState.value))
    }
}

private const val MAX_SAVED_CATALOG_SORTS = 128

internal fun updateCatalogSortSelections(
    previous: Map<String, CatalogSortOption>,
    key: String,
    selected: CatalogSortOption,
): Map<String, CatalogSortOption> {
    if (key.isBlank()) return previous
    val updated = LinkedHashMap(previous)
    updated.remove(key)
    if (selected != CatalogSortOption.PROVIDER_ORDER) updated[key] = selected
    while (updated.size > MAX_SAVED_CATALOG_SORTS) {
        updated.remove(updated.keys.first())
    }
    return updated
}

private val CatalogSortJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class StoredCatalogSortSettings(
    val selections: Map<String, String> = emptyMap(),
)

internal fun encodeCatalogSortSettings(state: CatalogSortSettingsUiState): String =
    CatalogSortJson.encodeToString(
        StoredCatalogSortSettings(state.selections.mapValues { (_, option) -> option.name }),
    )

internal fun decodeCatalogSortSettings(payload: String?): CatalogSortSettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { CatalogSortJson.decodeFromString<StoredCatalogSortSettings>(it) }.getOrNull() }
    val selections = stored?.selections.orEmpty().mapNotNull { (key, rawOption) ->
        val selected = CatalogSortOption.entries.firstOrNull { it.name == rawOption }
        if (key.isBlank() || selected == null || selected == CatalogSortOption.PROVIDER_ORDER) {
            null
        } else {
            key to selected
        }
    }.takeLast(MAX_SAVED_CATALOG_SORTS).toMap()
    return CatalogSortSettingsUiState(selections)
}

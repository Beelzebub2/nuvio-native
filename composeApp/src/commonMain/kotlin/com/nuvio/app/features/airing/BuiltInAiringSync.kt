package com.nuvio.app.features.airing

import kotlinx.serialization.json.JsonArray

internal const val AIRING_HIDDEN_TITLES_SYNC_KEY = "airing_hidden_titles"

internal data class HiddenAiringSyncState(val titles: JsonArray, val pending: Boolean)

// Null means this platform does not implement Airing Now; preserve the remote field.
internal expect suspend fun exportBuiltInAiringHiddenTitles(): HiddenAiringSyncState?
internal expect suspend fun applyBuiltInAiringHiddenTitles(titles: JsonArray, expected: JsonArray?)
internal expect suspend fun acknowledgeBuiltInAiringHiddenTitles(titles: JsonArray)

package com.nuvio.app.features.home

import com.nuvio.app.features.airing.AIRING_HIDDEN_TITLES_SYNC_KEY
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun `existing hidden list seeds absent field but never an explicit remote clear`() {
        val hidden = JsonArray(listOf(JsonPrimitive(42)))
        assertTrue(shouldSeedHiddenAiringTitles(null, hidden))
        assertTrue(shouldSeedHiddenAiringTitles(buildJsonObject { put("items", JsonArray(emptyList())) }, hidden))
        assertFalse(shouldSeedHiddenAiringTitles(buildJsonObject {
            put(AIRING_HIDDEN_TITLES_SYNC_KEY, JsonArray(emptyList()))
        }, hidden))
        assertFalse(shouldSeedHiddenAiringTitles(null, JsonArray(emptyList())))
    }

    @Test
    fun `shared blob carries hidden list clears and unsupported platforms preserve it`() {
        val remote = buildJsonObject {
            put(AIRING_HIDDEN_TITLES_SYNC_KEY, JsonArray(listOf(JsonPrimitive(42))))
            put("future_setting", "preserved")
        }
        val clear = buildJsonObject { put(AIRING_HIDDEN_TITLES_SYNC_KEY, JsonArray(emptyList())) }
        val merged = mergeHomeCatalogSettingsJson(remote, clear)
        assertEquals(JsonArray(emptyList()), merged[AIRING_HIDDEN_TITLES_SYNC_KEY])
        assertEquals(remote, mergeHomeCatalogSettingsJson(remote, buildJsonObject { }))
        assertEquals(JsonPrimitive("preserved"), merged["future_setting"])
    }

    @Test
    fun `shared settings merge preserves unknown remote fields`() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("show_catalog_type", false)
        }
        val local = buildJsonObject {
            put("show_catalog_type", true)
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.content.toBoolean())
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }
}

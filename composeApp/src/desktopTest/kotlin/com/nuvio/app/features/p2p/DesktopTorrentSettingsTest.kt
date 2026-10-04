package com.nuvio.app.features.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTorrentSettingsTest {
    private val defaults = Json.parseToJsonElement("""{"DisableDHT":false,"DisablePEX":false,"CustomFutureSetting":42}""").jsonObject

    @Test fun diskCacheAndUploadChoicesReachTheEngineWithoutLosingDefaults() {
        val configured = desktopTorrentSettings(defaults, P2pSettingsUiState(
            enableUpload = false, cacheSize = P2pCacheSize.GB_5, torrentProfile = P2pTorrentProfile.FAST,
        ), "C:/cache")
        assertEquals(P2pCacheSize.GB_5.bytes, configured.getValue("CacheSize").jsonPrimitive.long)
        assertTrue(configured.getValue("UseDisk").jsonPrimitive.boolean)
        assertTrue(configured.getValue("DisableUpload").jsonPrimitive.boolean)
        assertEquals(200, configured.getValue("ConnectionsLimit").jsonPrimitive.int)
        assertEquals("C:/cache", configured.getValue("TorrentsSavePath").jsonPrimitive.content)
        assertEquals(defaults.getValue("CustomFutureSetting"), configured.getValue("CustomFutureSetting"))
        assertFalse(configured.getValue("DisableDHT").jsonPrimitive.boolean)
    }

    @Test fun noPersistentCacheUsesBoundedMemoryForEveryProfile() {
        val expected = mapOf(P2pTorrentProfile.SOFT to 128L, P2pTorrentProfile.BALANCED to 256L, P2pTorrentProfile.FAST to 512L)
        expected.forEach { (profile, mib) ->
            val configured = desktopTorrentSettings(defaults, P2pSettingsUiState(cacheSize = P2pCacheSize.NONE, torrentProfile = profile), "C:/cache")
            assertFalse(configured.getValue("UseDisk").jsonPrimitive.boolean)
            assertEquals(mib * 1024 * 1024, configured.getValue("CacheSize").jsonPrimitive.long)
            assertEquals("", configured.getValue("TorrentsSavePath").jsonPrimitive.content)
        }
    }
}

package com.nuvio.app.features.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
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

    @Test fun playbackPreloadIsSizedForLargeEpisodesWithoutScalingLinearlyWithDiskCache() {
        val expectedDisk = mapOf(
            P2pCacheSize.GB_2 to 6,
            P2pCacheSize.GB_5 to 3,
            P2pCacheSize.GB_10 to 1,
        )
        expectedDisk.forEach { (size, percent) ->
            val config = desktopTorrentSettings(defaults, P2pSettingsUiState(cacheSize = size), "C:/playback-cache")
            assertEquals(percent, config.getValue("PreloadCache").jsonPrimitive.int)
            val preloadBytes = size.bytes * percent / 100
            assertTrue(preloadBytes in 96L * 1024 * 1024..160L * 1024 * 1024)
        }
        val expectedMemory = mapOf(
            P2pTorrentProfile.SOFT to 50,
            P2pTorrentProfile.BALANCED to 50,
            P2pTorrentProfile.FAST to 25,
        )
        expectedMemory.forEach { (profile, percent) ->
            val config = desktopTorrentSettings(defaults, P2pSettingsUiState(
                torrentProfile = profile, cacheSize = P2pCacheSize.NONE,
            ), "C:/memory-cache")
            assertEquals(percent, config.getValue("PreloadCache").jsonPrimitive.int)
        }
    }

    @Test fun corruptSettingsAreQuarantinedByteForByteWithoutTouchingOtherFiles() {
        val directory = Files.createTempDirectory("nuvio-corrupt-settings-test").toFile()
        try {
            val file = File(directory, "settings.json")
            val database = File(directory, "config.db").apply { writeText("retain database") }
            val oldBackup = File(directory, "settings-corrupt-existing.json").apply { writeText("retain backup") }
            val corruptions = listOf(ByteArray(1223), byteArrayOf(), byteArrayOf(0xff.toByte()), "{\"BitTorr\":".toByteArray(),
                "not json".toByteArray(), "[]".toByteArray(), "null".toByteArray(),
                "{\"BitTorr\":[]}".toByteArray(), "{\"BitTorr\":{\"StoreSettingsInJson\":{}}}".toByteArray())
            corruptions.forEach { bytes ->
                val previous = directory.listFiles()!!.toSet()
                file.writeBytes(bytes)
                prepareTorrServerSettingsFile(directory, File(directory, "cache"), P2pSettingsUiState())
                assertFalse(file.exists())
                val backup = (directory.listFiles()!!.toSet() - previous).single()
                assertTrue(backup.name.startsWith("settings-corrupt-"))
                assertContentEquals(bytes, backup.readBytes())
            }
            assertEquals("retain database", database.readText())
            assertEquals("retain backup", oldBackup.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun validSettingsWithoutJsonPersistenceStayByteForByteUnchanged() {
        val directory = Files.createTempDirectory("nuvio-valid-settings-test").toFile()
        try {
            val file = File(directory, "settings.json")
            listOf("""{ "BitTorr": { "StoreSettingsInJson": false, "EnableIPv6": true }, "Other": 42 }""",
                """{"Other":{"future":true}}""").forEach { text ->
                file.writeText(text)
                prepareTorrServerSettingsFile(directory, File(directory, "cache"), P2pSettingsUiState())
                assertEquals(text, file.readText())
                assertEquals(listOf(file), directory.listFiles()!!.toList())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun filesystemFailuresAreReportedWithoutBeingTreatedAsCorruptJson() {
        val directory = Files.createTempDirectory("nuvio-private-settings-test").toFile()
        try {
            val file = File(directory, "settings.json").apply { mkdir() }
            val sentinel = File(file, "private-data").apply { writeText("retain") }
            val error = assertFailsWith<P2pStreamingException> {
                prepareTorrServerSettingsFile(directory, File(directory, "cache"), P2pSettingsUiState())
            }
            assertEquals("Torrent engine settings could not be read or repaired. Check app storage permissions and retry.", error.message)
            assertFalse(error.toString().contains(directory.name))
            assertEquals("retain", sentinel.readText())
            assertEquals(listOf(file), directory.listFiles()!!.toList())
        } finally { directory.deleteRecursively() }
    }
}

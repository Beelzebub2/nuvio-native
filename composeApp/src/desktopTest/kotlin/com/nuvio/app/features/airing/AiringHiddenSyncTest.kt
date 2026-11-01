package com.nuvio.app.features.airing

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiringHiddenSyncTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val event = AiringEvent(42, 1, now.epochSecond - 60, "Anime", poster = "https://example.com/poster", isAdult = false)
    private val snapshot = AiringSnapshot(listOf(event), now.toEpochMilli(), hideNsfw = true, contentPolicyVersion = AIRING_CONTENT_POLICY_VERSION)
    private val json = Json { encodeDefaults = true }

    @Test
    fun `two devices sync hides and restoring the final hidden title without fetching or feedback`() = runBlocking {
        var pushesA = 0
        var pushesB = 0
        val deviceA = AiringRepository(AiringSettings(), snapshot, fetch = { _, _ -> error("No refetch") }, now = { now }, onHiddenChanged = { pushesA++ })
        val deviceB = AiringRepository(AiringSettings(zone = "UTC", historyDays = 5), snapshot, fetch = { _, _ -> error("No refetch") }, now = { now }, onHiddenChanged = { pushesB++ })

        deviceA.hide(event)
        val hidden = deviceA.exportHiddenTitles()
        assertTrue(hidden.pending)
        deviceB.applyHiddenTitles(hidden.titles)
        deviceA.acknowledgeHiddenTitles(hidden.titles)
        assertEquals(deviceA.state.value.settings.hidden, deviceB.state.value.settings.hidden)
        assertEquals("UTC", deviceB.state.value.settings.zone)
        assertEquals(5, deviceB.state.value.settings.historyDays)
        assertEquals(snapshot, deviceB.state.value.snapshot)
        assertTrue(selectAiringEvents(snapshot.events, deviceB.state.value.settings, now, AiringWindow.Now).isEmpty())

        deviceA.showAgain(event.mediaId)
        val restored = deviceA.exportHiddenTitles()
        assertEquals(JsonArray(emptyList()), restored.titles)
        assertTrue(restored.pending)
        deviceB.applyHiddenTitles(restored.titles)
        assertEquals(listOf(event), selectAiringEvents(snapshot.events, deviceB.state.value.settings, now, AiringWindow.Now))
        assertEquals(2, pushesA)
        assertEquals(0, pushesB)
        assertFalse(deviceB.exportHiddenTitles().pending)
    }

    @Test
    fun `offline edits survive restart stale pulls and acknowledgements`() = runBlocking {
        var saved = json.encodeToString(AiringSettings())
        val device = AiringRepository(AiringSettings(), snapshot, fetch = { _, _ -> emptyList() }, saveSettings = { saved = json.encodeToString(it) })
        device.hide(event)
        val earlierUpload = device.exportHiddenTitles().titles
        device.acknowledgeHiddenTitles(earlierUpload)
        device.showAllAgain()
        val restarted = AiringRepository(AiringSettings(), fetch = { _, _ -> error("No fetch") },
            loadSaved = { decodeSavedAiringSettings(saved) to snapshot }, saveSettings = { saved = json.encodeToString(it) })

        restarted.applyHiddenTitles(earlierUpload)
        restarted.acknowledgeHiddenTitles(earlierUpload)
        assertTrue(restarted.state.value.settings.hidden.isEmpty())
        assertTrue(restarted.exportHiddenTitles().pending)
        restarted.acknowledgeHiddenTitles(JsonArray(emptyList()))
        assertFalse(restarted.exportHiddenTitles().pending)
        assertFalse(decodeSavedAiringSettings(saved).hiddenSyncPending)
    }

    @Test
    fun `stale general settings cannot resurrect titles or clear pending hides`() = runBlocking {
        val original = AiringSettings()
        val device = AiringRepository(original, snapshot, fetch = { _, _ -> error("No fetch") }, now = { now })
        device.hide(event)
        device.updateSettings(original.copy(zone = "UTC"))
        assertEquals(listOf(event.mediaId), device.state.value.settings.hidden.map { it.mediaId })
        assertTrue(device.exportHiddenTitles().pending)
        val beforePull = device.exportHiddenTitles().titles
        device.acknowledgeHiddenTitles(beforePull)
        device.applyHiddenTitles(JsonArray(emptyList()), expected = JsonArray(emptyList()))
        assertEquals(beforePull, device.exportHiddenTitles().titles)
    }

    @Test
    fun `legacy desktop lists migrate once to one profile and remain uploadable`() = runBlocking {
        val directory = Files.createTempDirectory("airing-hidden-sync")
        val file = directory.resolve("airing.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("settings", """{"zone":"UTC","hidden":[{"mediaId":42,"name":"Anime"}]}""")
            val primary = decodeSavedAiringSettings(NativeAiring.loadScopedSettings("account:profile1", store))
            val secondary = decodeSavedAiringSettings(NativeAiring.loadScopedSettings("account:profile2", store))
            assertEquals(listOf(42), primary.hidden.map { it.mediaId })
            assertTrue(primary.hiddenSyncPending)
            assertTrue(secondary.hidden.isEmpty())
            assertFalse(secondary.hiddenSyncPending)
            assertEquals("UTC", secondary.zone)
            assertEquals(primary, decodeSavedAiringSettings(NativeAiring.loadScopedSettings("account:profile1", store)))
            val device = AiringRepository(AiringSettings(), fetch = { _, _ -> error("No fetch") }, loadSaved = { primary to snapshot })
            assertTrue(device.exportHiddenTitles().pending)
        } finally {
            Files.deleteIfExists(file)
            // DesktopStorage can keep recovery files beside its primary file.
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

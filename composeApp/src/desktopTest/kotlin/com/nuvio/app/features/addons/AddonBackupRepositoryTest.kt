package com.nuvio.app.features.addons

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonBackupRepositoryTest {
    @Test
    fun importValidatesBeforeMutationAndPersistsOfflineEntriesAndProfileGuards() {
        val originalProfile = ProfileStorage.loadPayload()
        val originalUrls = AddonStorage.loadInstalledAddonUrls(1)
        val originalStates = AddonStorage.loadAddonEnabledStates(1)
        val originalNames = AddonStorage.loadAddonNames(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
        server.start()
        fun loadProfile(index: Int, usesPrimary: Boolean = false) {
            ProfileStorage.savePayload("""{"userId":"addon-backup-test","activeProfileIndex":$index,"profiles":[
                {"profile_index":1,"name":"Primary"},
                {"profile_index":2,"name":"Secondary","uses_primary_addons":$usesPrimary}
            ]}""")
            ProfileRepository.clearInMemory()
            ProfileRepository.ensureLoaded("addon-backup-test")
            AddonRepository.clearLocalState()
            AddonRepository.initialize()
        }
        try {
            AddonStorage.saveInstalledAddonUrls(1, emptyList())
            AddonStorage.saveAddonEnabledStates(1, emptyMap())
            AddonStorage.saveAddonNames(1, emptyMap())
            loadProfile(1)
            val validUrl = "http://127.0.0.1:${server.address.port}/manifest.json?private=abc%2Bdef"
            val invalid = """{"format":"starlit-addons","version":1,"addons":[
                {"manifestUrl":"$validUrl"},{"manifestUrl":"file:///bad"}
            ]}"""
            assertEquals(AddonBackupError.Invalid, assertFailsWith<AddonBackupException> {
                AddonRepository.importBackup(invalid, 1, AddonRepository.backupProfileRevision)
            }.reason)
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            assertTrue(AddonStorage.loadInstalledAddonUrls(1).isEmpty())

            val backup = AddonBackupCodec.encode(listOf(
                ManagedAddon(validUrl, userSetName = "Offline configured add-on"),
                ManagedAddon("https://disabled.example.test/manifest.json", enabled = false, userSetName = "Disabled"),
            ))
            val result = AddonRepository.importBackup(backup, 1, AddonRepository.backupProfileRevision)
            assertEquals(2, result.imported)
            val deadline = System.currentTimeMillis() + 5000
            while (AddonRepository.uiState.value.addons.any { it.isRefreshing } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(AddonRepository.uiState.value.addons.first().isRefreshing)
            assertTrue(AddonRepository.uiState.value.addons.first().errorMessage != null)
            assertEquals(2, AddonRepository.uiState.value.addons.size)
            assertEquals(2, AddonRepository.importBackup(backup, 1, AddonRepository.backupProfileRevision).skipped)
            val staleRevision = AddonRepository.backupProfileRevision
            AddonRepository.clearLocalState()
            AddonRepository.initialize()
            assertEquals(backup, AddonRepository.exportBackup())
            assertEquals("Offline configured add-on", AddonStorage.loadAddonNames(1)[validUrl])
            assertEquals(false, AddonStorage.loadAddonEnabledStates(1)["https://disabled.example.test/manifest.json"])
            assertEquals(AddonBackupError.ProfileChanged, assertFailsWith<AddonBackupException> {
                AddonRepository.importBackup(backup, 1, staleRevision)
            }.reason)
            assertEquals(AddonBackupError.ProfileChanged, assertFailsWith<AddonBackupException> {
                AddonRepository.importBackup(backup, 2, AddonRepository.backupProfileRevision)
            }.reason)
            loadProfile(2, usesPrimary = true)
            assertEquals(AddonBackupError.PrimaryProfileRequired, assertFailsWith<AddonBackupException> {
                AddonRepository.importBackup(backup, 2, AddonRepository.backupProfileRevision)
            }.reason)
        } finally {
            AddonRepository.clearLocalState()
            server.stop(0)
            AddonStorage.saveInstalledAddonUrls(1, originalUrls)
            AddonStorage.saveAddonEnabledStates(1, originalStates)
            AddonStorage.saveAddonNames(1, originalNames)
            ProfileStorage.savePayload(originalProfile.orEmpty())
            ProfileRepository.clearInMemory()
            ProfileRepository.loadCachedProfiles()
        }
    }
}

package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AddonBackupTest {
    private val configuredUrl = "https://example.test/config=abc%7Cxyz/manifest.json?token=a%2Bb&other=1"

    @Test
    fun roundTripKeepsConfiguredUrlsOrderDisabledStateAndNames() {
        val addons = listOf(
            ManagedAddon(configuredUrl, userSetName = "My configured add-on", enabled = false),
            ManagedAddon("http://localhost:8080/manifest.json"),
        )
        val entries = AddonBackupCodec.decode(AddonBackupCodec.encode(addons))
        val restored = mergeAddonBackup(emptyList(), entries)
        assertEquals(addons, restored.addons)
        assertEquals(2, restored.imported)
        assertFalse(restored.addons.first().enabled)
        assertEquals(emptyList(), AddonBackupCodec.decode(AddonBackupCodec.encode(emptyList())))
        // Preserve the serializer's compatible quoted-Boolean handling without changing the state.
        assertFalse(AddonBackupCodec.decode("""{"format":"starlit-addons","version":1,"addons":[{"manifestUrl":"$configuredUrl","enabled":"false"}]}""").single().enabled)
    }

    @Test
    fun mergeSkipsDuplicatesWithoutChangingExistingEntries() {
        val existing = ManagedAddon(configuredUrl, enabled = false, userSetName = "Keep me")
        val entries = AddonBackupCodec.decode("""{"format":"starlit-addons","version":1,"addons":[
            {"manifestUrl":"$configuredUrl"},
            {"manifestUrl":"https://other.test/","enabled":false,"name":"Offline"},
            {"manifestUrl":"https://other.test/manifest.json"}
        ]}""")
        val merged = mergeAddonBackup(listOf(existing), entries)
        assertEquals(existing, merged.addons.first())
        assertEquals("https://other.test/manifest.json", merged.addons.last().manifestUrl)
        assertEquals(1, merged.imported)
        assertEquals(2, merged.skipped)
        assertEquals("Offline", merged.addons.last().userSetName)
    }

    @Test
    fun rejectsMalformedUnsupportedAndOversizedBackups() {
        fun reason(payload: String) = assertFailsWith<AddonBackupException> { AddonBackupCodec.decode(payload) }.reason
        assertEquals(AddonBackupError.Invalid, reason("not json"))
        assertEquals(AddonBackupError.Invalid, reason("""{"addons":[]}"""))
        assertEquals(AddonBackupError.Invalid, reason("""{"format":"other-app","version":1,"addons":[]}"""))
        assertEquals(AddonBackupError.UnsupportedVersion, reason("""{"format":"starlit-addons","version":2,"addons":[]}"""))
        assertEquals(AddonBackupError.TooLarge, reason(" ".repeat(AddonBackupMaxBytes + 1)))
        for (url in listOf("file:///tmp/addon", "javascript:alert(1)", "https:///", "https://", "https://:443/", "https://user@/", "https://ex ample.test/", "https://example.test/#fragment", "https://example.test:99999/")) {
            assertEquals(AddonBackupError.Invalid, reason("""{"format":"starlit-addons","version":1,"addons":[{"manifestUrl":"$url"}]}"""), url)
        }
        assertEquals(AddonBackupError.Invalid, reason("""{"format":"starlit-addons","version":1,"addons":[{"manifestUrl":"https://example.test/","enabled":"not-a-boolean"}]}"""))
    }
}

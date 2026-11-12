package com.nuvio.app.features.myanimelist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
class MalAccountSyncTest {
    @Test fun cloudCredentialRoundtripPreservesOAuthFieldsWithoutChangingOrdinarySettings() {
        val tokens = MalTokens("public-client-123456", "access-token", "refresh-token", 2000000000000L, "Account", "optional-secret")
        assertEquals(tokens, parseMalAccountCredential(malAccountCredential(tokens)))
    }
    @Test fun blankAndMalformedCredentialsCannotBecomeLinkedSessions() {
        assertNull(parseMalAccountCredential(buildJsonObject { put("client_id", "short") }))
        val tokens = MalTokens("public-client-123456", "access-token", "refresh-token", 2000000000000L)
        assertNull(parseMalAccountCredential(buildJsonObject { malAccountCredential(tokens).forEach { (key, value) -> put(key, value) }; put("refresh_token", "") }))
    }
}

package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PasskeyRepositoryTest {
    @Test
    fun providerAssociationErrorsOfferSafeAlternativeWithoutDisablingVerification() {
        for (message in listOf(
            "Passkeys not supported for this app",
            "Passkey operation failed because of missing asset links",
            "app not supported",
        )) {
            val result = passkeyProviderErrorMessage(message, registration = true)
            assertTrue(result.contains("Bitwarden"))
            assertTrue(result.contains("Google Password Manager"))
            assertTrue(result.contains("cannot bypass"))
            assertFalse(result.contains("missing asset links"))
        }
        assertTrue(passkeyProviderErrorMessage("No provider configuration", true).contains("Enable one"))
        assertEquals("Passkey operation canceled.", passkeyProviderErrorMessage("user canceled", false))
    }

    @Test
    fun passesRequireExplicitOfficialCapability() {
        val endpoint = "https://starlit-api.rrmtools.uk"
        assertTrue(isPasskeyAdvertised("""{"backend_url":"$endpoint","capabilities":{"passkey_login":true}}"""))
        for (document in listOf("{}", "not-json", """{"backend_url":"$endpoint","capabilities":{}}""",
            """{"backend_url":"$endpoint","capabilities":{"passkey_login":false}}""",
            """{"backend_url":"$endpoint","capabilities":{"passkey_login":"true"}}""",
            """{"backend_url":"https://attacker.test","capabilities":{"passkey_login":true}}""")) {
            assertFalse(isPasskeyAdvertised(document), "Unexpected passkey capability: $document")
        }
    }

    @Test
    fun onlyTheExactOfficialHttpsRelyingPartyCanBeUsed() {
        assertTrue(isTrustedPasskeyOrigin("https://starlit-api.rrmtools.uk"))
        assertTrue(isTrustedPasskeyOrigin("https://starlit-api.rrmtools.uk/"))
        for (url in listOf(
            "http://starlit-api.rrmtools.uk", "https://starlit-api.rrmtools.uk.evil.test",
            "https://other.rrmtools.uk", "https://starlit-api.rrmtools.uk:443",
            "https://starlit-api.rrmtools.uk/auth/v1", "https://user@starlit-api.rrmtools.uk",
            "https://starlit-api.rrmtools.uk?redirect=evil", "",
        )) {
            assertFalse(isTrustedPasskeyOrigin(url), "Accepted a non-Starlit RP: $url")
        }
    }

    @Test
    fun parsesTheServerGeneratedChallengeAndNativeOptions() {
        val options = parsePasskeyOptions("""{
            "challenge_id":"5df2038a-34da-4d6f-a5b8-a5af9890a5e6",
            "expires_at":1793635200,
            "options":{"challenge":"YWJj","rpId":"starlit-api.rrmtools.uk","userVerification":"required"}
        }""")
        assertEquals("5df2038a-34da-4d6f-a5b8-a5af9890a5e6", options.challengeId)
        assertEquals("YWJj", Json.parseToJsonElement(options.optionsJson).jsonObject.getValue("challenge").jsonPrimitive.content)
    }

    @Test
    fun malformedOrMissingServerChallengesFailClosed() {
        for (json in listOf("{}", "[]", "not-json", """{"challenge_id":"?","options":{"challenge":"abc"}}""",
            """{"challenge_id":"5df2038a-34da-4d6f-a5b8-a5af9890a5e6","options":{}}""",
            """{"challenge_id":"5df2038a-34da-4d6f-a5b8-a5af9890a5e6","options":null}""")) {
            assertFailsWith<Exception> { parsePasskeyOptions(json) }
        }
    }

    @Test
    fun credentialIsSentAsJsonObjectNotAnEscapedString() {
        val body = passkeyVerificationBody("5df2038a-34da-4d6f-a5b8-a5af9890a5e6",
            """{"id":"credential-id","rawId":"YWJj","type":"public-key","response":{"clientDataJSON":"abc"}}""")
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("5df2038a-34da-4d6f-a5b8-a5af9890a5e6", parsed.getValue("challenge_id").jsonPrimitive.content)
        assertEquals("credential-id", parsed.getValue("credential").jsonObject.getValue("id").jsonPrimitive.content)
        assertFailsWith<Exception> { passkeyVerificationBody("abc", """{"type":"public-key"}""") }
        assertFailsWith<Exception> { passkeyVerificationBody("abc", "{}") }
        assertFailsWith<Exception> { passkeyVerificationBody("abc", "invalid") }
    }

    @Test
    fun registeredPasskeysAreReadFromListAndWrappedResponsesWithoutLosingMetadata() {
        val passkey = """{"id":"73a4ea39-8f72-47aa-b324-5522c8d53e10","friendly_name":"Bitwarden",
            "created_at":"2026-11-03T09:20:00Z"}"""
        for (body in listOf("[$passkey]", """{"passkeys":[$passkey]}""", """{"credentials":[$passkey]}""")) {
            val keys = parseRegisteredPasskeys(body)
            assertEquals(1, keys.size)
            assertEquals("73a4ea39-8f72-47aa-b324-5522c8d53e10", keys.single().id)
            assertEquals("Bitwarden", keys.single().name)
            assertEquals("2026-11-03T09:20:00Z", keys.single().createdAt)
        }
        assertEquals(emptyList(), parseRegisteredPasskeys("[]"))
        assertEquals(emptyList(), parseRegisteredPasskeys("""{"passkeys":[]}"""))
        assertEquals("Passkey", parseRegisteredPasskeys("""[{"id":"abc","friendly_name":null}]""").single().name)
    }

    @Test
    fun malformedPasskeyListNeverLooksLikeAnEmptyAccount() {
        for (body in listOf(
            "", "not-json", "null", "{}", """{"passkeys":{}}""",
            """{"credentials":null}""", """{"passkeys":[{}]}""",
            """{"passkeys":[{"id":null}]}""", """{"passkeys":[{"id":""}]}""",
        )) {
            assertFailsWith<Exception>("Unexpectedly treated $body as an empty passkey list") {
                parseRegisteredPasskeys(body)
            }
        }
    }
}

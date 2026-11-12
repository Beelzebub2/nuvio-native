package com.nuvio.app.features.trakt

import io.ktor.http.Url
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraktPkceTest {
    private val redirect = "https://example.org/connect/trakt/"

    @Test
    fun rfc7636S256ChallengeMatchesKnownVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", traktPkceChallenge(verifier))
        assertFalse(isValidTraktPkceVerifier(verifier.dropLast(1)))
        assertFalse(isValidTraktPkceVerifier("a".repeat(130)))
        assertFalse(isValidTraktPkceVerifier("a".repeat(42) + "/"))
    }

    @Test
    fun eachAuthenticationHasIndependentVerifierAndState() {
        val first = generateTraktPkceMaterial()
        val second = generateTraktPkceMaterial()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.challenge, second.challenge)
        assertTrue(isValidTraktPkceVerifier(first.verifier))
        assertEquals(traktPkceChallenge(first.verifier), first.challenge)
        assertFailsWith<IllegalArgumentException> { createTraktPkceMaterial(ByteArray(31), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { createTraktPkceMaterial(ByteArray(32), ByteArray(15)) }
    }

    @Test
    fun authorizationRequestContainsS256ButNotVerifierOrSecret() {
        val material = createTraktPkceMaterial(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 1).toByte() })
        val url = buildTraktPkceAuthorizationUrl("public-client", redirect, material)
        val parsed = Url(url)
        assertEquals("auth.trakt.tv", parsed.host)
        assertEquals("code", parsed.parameters["response_type"])
        assertEquals("public-client", parsed.parameters["client_id"])
        assertEquals(redirect, parsed.parameters["redirect_uri"])
        assertEquals(material.state, parsed.parameters["state"])
        assertEquals(material.challenge, parsed.parameters["code_challenge"])
        assertEquals("S256", parsed.parameters["code_challenge_method"])
        assertFalse(url.contains(material.verifier))
        assertFalse(url.contains("client_secret"))
    }

    @Test
    fun callbacksRequireRegisteredHttpsHostPathAndPort() {
        assertTrue(isValidTraktRedirectUri(redirect))
        assertTrue(isTraktRedirectCallback(redirect + "?code=valid&state=valid", redirect))
        listOf(
            "nuvio://auth/trakt?code=test",
            "http://example.org/connect/trakt/?code=test",
            "https://example.org.evil.test/connect/trakt/?code=test",
            "https://example.org/connect/trakt/extra?code=test",
            "https://example.org/connect/trakt?code=test",
            "https://example.org:444/connect/trakt/?code=test",
            "https://user@example.org/connect/trakt/?code=test",
            "https://example.org/connect/trakt/?code=test#fragment",
            "https://example.org/connect/trakt/?code=a&state=x&state=y",
            "https://example.org/connect/trakt/?code=a&code=b&state=x",
        ).forEach { candidate ->
            assertFalse(isTraktRedirectCallback(candidate, redirect), candidate)
        }
        listOf(
            "nuvio://auth/trakt",
            "http://example.org/connect/trakt/",
            "https://example.org/connect/trakt/?ready=1",
            "https://example.org/connect/trakt/#fragment",
        ).forEach { candidate ->
            assertFalse(isValidTraktRedirectUri(candidate), candidate)
        }
    }

    @Test
    fun deviceCodeIsFallbackWithoutValidatedCallback() {
        assertTrue(shouldUseTraktDeviceAuthorization(true, redirect))
        assertTrue(shouldUseTraktDeviceAuthorization(false, ""))
        assertTrue(shouldUseTraktDeviceAuthorization(false, "nuvio://auth/trakt"))
        assertFalse(shouldUseTraktDeviceAuthorization(false, redirect))
    }

    @Test
    fun publicOauthTokenRequestsNeverSendClientSecrets() {
        val encoder = Json { encodeDefaults = true }
        val verifier = generateTraktPkceMaterial().verifier
        val authorization = encoder.encodeToString(
            TraktAuthorizationCodeRequest("code", "public-id", verifier, redirect),
        )
        assertTrue(authorization.contains("\"code_verifier\""))
        assertTrue(authorization.contains("\"grant_type\":\"authorization_code\""))
        val device = encoder.encodeToString(TraktDeviceTokenRequest("device-code", "public-id"))
        val refresh = buildTraktRefreshTokenBody("refresh-value", "public-id", redirect)
        val deviceRefresh = buildTraktRefreshTokenBody("refresh-value", "public-id", null)
        val revoke = encoder.encodeToString(TraktRevokeRequest("token", "public-id"))
        listOf(authorization, device, refresh, deviceRefresh, revoke).forEach {
            assertFalse(it.contains("client_secret"), it)
        }
        assertTrue(refresh.contains("\"grant_type\":\"refresh_token\""))
        assertTrue(refresh.contains("\"redirect_uri\""))
        assertFalse(deviceRefresh.contains("\"redirect_uri\""))
        assertTrue(device.contains("\"client_id\""))
        assertTrue(revoke.contains("\"client_id\""))
    }

    @Test
    fun legacyPersistedSessionsRemainReadable() {
        val parsed = Json.decodeFromString<TraktAuthState>(
            """{"accessToken":"old","refreshToken":"old-refresh","pendingAuthorizationState":"legacy"}""",
        )
        assertEquals(null, parsed.pendingCodeVerifier)
        assertTrue(parsed.isAuthenticated)
    }
}

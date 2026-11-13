package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklPinAuthorizationTest {
    @Test
    fun `v2 device authorization validates code and trusted verification URL`() {
        val pending = SimklDeviceResponse(
            deviceCode = "private-device-code",
            userCode = "ABCDE",
            verificationUri = "https://simkl.com/pin",
            verificationUriComplete = "https://simkl.com/pin?user_code=ABCDE",
            expiresIn = 900,
            interval = 5,
        ).toPendingAuthorization(nowEpochMs = 1_000L)

        assertEquals("private-device-code", pending?.deviceCode)
        assertEquals("ABCDE", pending?.userCode)
        assertEquals("https://simkl.com/pin?user_code=ABCDE", pending?.verificationUrl)
        assertEquals(5, pending?.intervalSeconds)
        assertEquals(901_000L, pending?.expiresAtEpochMs)
    }

    @Test
    fun `v2 device authorization rejects missing fields invalid TTL and untrusted URLs`() {
        assertNull(SimklDeviceResponse(userCode = "ABCDE").toPendingAuthorization(0L))
        assertNull(SimklDeviceResponse(deviceCode = "private", userCode = "ABCDE", verificationUri = "https://evil.example/pin", expiresIn = 900, interval = 5).toPendingAuthorization(0L))
        assertNull(SimklDeviceResponse(deviceCode = "private", userCode = "ABCDE", verificationUri = "http://simkl.com/pin", expiresIn = 900, interval = 5).toPendingAuthorization(0L))
        assertNull(SimklDeviceResponse(deviceCode = "private", userCode = "ABCDE", verificationUri = "https://simkl.com/pin", expiresIn = 0, interval = 5).toPendingAuthorization(0L))
        assertNull(SimklDeviceResponse(deviceCode = "private", userCode = "ABCDE", verificationUri = "https://simkl.com/pin", expiresIn = 900, interval = 0).toPendingAuthorization(0L))
    }

    @Test
    fun `v2 tokens require owner-scoped read and write access refresh token and expiry`() {
        val response = SimklDeviceTokenResponse(
            accessToken = "access",
            refreshToken = "refresh",
            expiresIn = 604_800,
            tokenType = "Bearer",
            scope = SIMKL_V2_SCOPES,
        )
        val credentials = assertNotNull(response.toCredentials(100L))
        assertEquals("access", credentials.accessToken)
        assertEquals("refresh", credentials.refreshToken)
        assertEquals(604_800_100L, credentials.expiresAtEpochMs)
        assertEquals("previous", response.copy(refreshToken = null).toCredentials(100L, "previous")?.refreshToken)
        assertNull(response.copy(refreshToken = null).toCredentials(100L))
        assertNull(response.copy(scope = "media:read").toCredentials(100L))
        assertNull(response.copy(tokenType = "Basic").toCredentials(100L))
        assertNull(response.copy(expiresIn = 0).toCredentials(100L))
        assertNull(response.toCredentials(Long.MAX_VALUE))
    }

    @Test
    fun `v2 device polling distinguishes pending slow down and expiration`() {
        assertEquals(SimklPinPollResult.Pending, simklPinOAuthError("authorization_pending"))
        assertEquals(SimklPinPollResult.SlowDown, simklPinOAuthError("slow_down"))
        assertEquals(SimklPinPollResult.Gone, simklPinOAuthError("expired_token"))
        assertEquals(SimklPinPollResult.Gone, simklPinOAuthError("access_denied"))
        assertEquals(SimklPinPollResult.Failed, simklPinOAuthError("invalid_client"))
    }

    @Test
    fun `v2 pins honor the server supplied expiration time`() {
        assertFalse(isSimklPinAuthorizationExpired(10_000L, 9_999L))
        assertTrue(isSimklPinAuthorizationExpired(10_000L, 10_000L))
        assertTrue(isSimklPinAuthorizationExpired(null, 1L))
    }
}

package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktAuthRepositoryTest {
    @Test
    fun `OAuth callbacks require an unexpired, exact pending state`() {
        val now = 1_000_000L
        val state = "SecureRandomState-012345678901234567890123"
        assertTrue(validTraktCallbackState(state, state, now - 5_000, now))
        assertFalse(validTraktCallbackState(null, state, now - 5_000, now))
        assertFalse(validTraktCallbackState(state, "", now - 5_000, now))
        assertFalse(validTraktCallbackState(state, state.dropLast(1) + "A", now - 5_000, now))
        assertFalse(validTraktCallbackState(state, state, null, now))
        assertFalse(validTraktCallbackState(state, state, now + 1, now))
        assertFalse(validTraktCallbackState(state, state, now - TRAKT_OAUTH_STATE_LIFETIME_MS, now))
        // The pending state is consumed before exchanging the code; a second callback has none.
        assertFalse(validTraktCallbackState(null, state, null, now))
    }

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }
}

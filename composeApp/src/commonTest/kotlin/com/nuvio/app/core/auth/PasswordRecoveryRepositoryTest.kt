package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordRecoveryRepositoryTest {
    @Test
    fun onlyOfficialHttpsOriginMayRequestPasswordReset() {
        assertTrue(isOfficialPasswordRecoveryServer("https://starlit-api.rrmtools.uk"))
        assertTrue(isOfficialPasswordRecoveryServer("https://starlit-api.rrmtools.uk/"))
        assertFalse(isOfficialPasswordRecoveryServer("http://starlit-api.rrmtools.uk"))
        assertFalse(isOfficialPasswordRecoveryServer("https://starlit-api.rrmtools.uk.evil.test"))
        assertFalse(isOfficialPasswordRecoveryServer("https://starlit-api.rrmtools.uk/other"))
        assertFalse(isOfficialPasswordRecoveryServer("https://evil.test@starlit-api.rrmtools.uk"))
        assertEquals("https://starlit-api.rrmtools.uk/account/recovery", STARLIT_PASSWORD_RECOVERY_URL)
    }

    @Test
    fun recoveryEmailValidationIsBoundedAndSafe() {
        assertEquals("person@example.com", normalizePasswordRecoveryEmail(" Person@Example.COM  "))
        listOf(
            "",
            "not-an-email",
            "a@b",
            "a@.test",
            "a@example.com\r\nBcc:you@example.com",
            "a" + "x".repeat(254) + "@example.com",
        ).forEach { value -> assertNull(normalizePasswordRecoveryEmail(value)) }
    }
}

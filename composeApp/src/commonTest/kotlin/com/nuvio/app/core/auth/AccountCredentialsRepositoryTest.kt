package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCredentialsRepositoryTest {
    @Test
    fun credentialMutationIsRestrictedToTheConfiguredOfficialServer() {
        assertTrue(isOfficialCredentialServer("https://starlit-api.rrmtools.uk"))
        assertTrue(isOfficialCredentialServer("https://starlit-api.rrmtools.uk/"))
        assertFalse(isOfficialCredentialServer("http://starlit-api.rrmtools.uk"))
        assertFalse(isOfficialCredentialServer("https://starlit-api.rrmtools.uk.evil.example"))
        assertFalse(isOfficialCredentialServer("https://evil.test@starlit-api.rrmtools.uk"))
    }

    @Test
    fun passwordMustBeAtLeastTwelveCharactersAndMatchConfirmation() {
        assertEquals(CredentialValidation.WEAK_PASSWORD, validatePasswordChange("short", "short"))
        assertEquals(CredentialValidation.WEAK_PASSWORD, validatePasswordChange("X".repeat(129), "X".repeat(129)))
        assertEquals(CredentialValidation.PASSWORDS_DIFFER,
            validatePasswordChange("good-new-password", "not-the-same-pass"))
        assertEquals(CredentialValidation.VALID,
            validatePasswordChange("long password with spaces", "long password with spaces"))
    }

    @Test
    fun emailAddressMustChangeAndBothFieldsMustMatch() {
        assertEquals(CredentialValidation.INVALID_EMAIL,
            validateEmailChange("old@example.com", "new@example.com\r\nBcc:bad@example.com",
                "new@example.com\r\nBcc:bad@example.com"))
        assertEquals(CredentialValidation.EMAILS_DIFFER,
            validateEmailChange("old@example.com", "new@example.com", "mistyped@example.com"))
        assertEquals(CredentialValidation.EMAIL_UNCHANGED,
            validateEmailChange("old@example.com", "OLD@example.com", "old@example.com"))
        assertEquals(CredentialValidation.VALID,
            validateEmailChange("old@example.com", " New@Example.COM ", "new@example.com"))
    }

    @Test
    fun emailReauthenticationCodeMustBeBoundedNumeric() {
        assertFalse(isValidCredentialNonce(""))
        assertFalse(isValidCredentialNonce("12345"))
        assertFalse(isValidCredentialNonce("123456789"))
        assertFalse(isValidCredentialNonce("12345a"))
        assertFalse(isValidCredentialNonce("123 456"))
        assertFalse(isValidCredentialNonce("123456\r\n"))
        assertTrue(isValidCredentialNonce("123456"))
        assertTrue(isValidCredentialNonce("12345678"))
    }
}
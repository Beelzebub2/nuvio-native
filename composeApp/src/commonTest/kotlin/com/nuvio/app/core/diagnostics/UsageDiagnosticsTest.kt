package com.nuvio.app.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageDiagnosticsTest {
    @Test
    fun consentIsUndecidedUntilPersistedOptIn() {
        assertEquals(DiagnosticsConsent.Undecided, consentFromStoredPreference(null))
        assertEquals(DiagnosticsConsent.Undecided, consentFromStoredPreference(""))
        assertEquals(DiagnosticsConsent.Undecided, consentFromStoredPreference("unexpected"))
        assertEquals(DiagnosticsConsent.Disabled, consentFromStoredPreference("no"))
        assertEquals(DiagnosticsConsent.Enabled, consentFromStoredPreference("yes"))
    }

    @Test
    fun versionsRejectArbitraryTextAndUrls() {
        assertTrue(isValidUsageVersion("1.9.2"))
        assertTrue(isValidUsageVersion("1.9.2-beta.1"))
        assertFalse(isValidUsageVersion(""))
        assertFalse(isValidUsageVersion("debug"))
        assertFalse(isValidUsageVersion("1.9.2/../../private"))
        assertFalse(isValidUsageVersion("https://example.com"))
    }

    @Test
    fun eventNamesCannotContainMediaOrUserValues() {
        val names = UsageEvent.entries.map { it.wireName }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all { it.matches(Regex("[a-z_]+")) })
        assertTrue("app_open" in names)
        assertTrue("playback_failed" in names)
        assertTrue("download_completed" in names)
    }
}

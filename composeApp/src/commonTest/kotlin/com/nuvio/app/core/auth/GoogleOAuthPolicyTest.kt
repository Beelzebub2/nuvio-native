package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleOAuthPolicyTest {
    @Test
    fun onlyGoogleAuthorizationHostIsAcceptedForAccountLinking() {
        assertTrue(isTrustedGoogleAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=abc"))
        for (url in listOf(
            "https://accounts.google.com.evil.test/o/oauth2/v2/auth",
            "http://accounts.google.com/o/oauth2/v2/auth",
            "https://user@attacker.test/o/oauth2/v2/auth",
            "https://accounts.google.com/not-oauth",
            "https://accounts.google.com/o/oauth2/v2/auth#access_token=bad",
        )) assertFalse(isTrustedGoogleAuthorizationUrl(url), url)
    }

    @Test
    fun linkingNeverAcceptsAnotherAccountOrServer() {
        assertTrue(isValidGoogleLinkResult("account-a", "account-a", "account-a", true))
        assertFalse(isValidGoogleLinkResult("account-a", "account-b", "account-a", true))
        assertFalse(isValidGoogleLinkResult("account-a", "account-a", "account-b", true))
        assertFalse(isValidGoogleLinkResult("account-a", null, "account-a", true))
        assertFalse(isValidGoogleLinkResult("account-a", "account-a", "account-a", false))
    }

    @Test
    fun googleRequiresExplicitOfficialBooleanCapability() {
        assertTrue(isGoogleLoginAdvertised("""{"backend_url":"$STARLIT_GOOGLE_ORIGIN","capabilities":{"google_login":true}}"""))
        for (document in listOf("{}", "invalid", """{"backend_url":"$STARLIT_GOOGLE_ORIGIN","capabilities":{"google_login":"true"}}""",
            """{"backend_url":"$STARLIT_GOOGLE_ORIGIN","capabilities":{"google_login":false}}""",
            """{"backend_url":"https://untrusted.example","capabilities":{"google_login":true}}""")) {
            assertFalse(isGoogleLoginAdvertised(document))
        }
    }

    @Test
    fun callbackAcceptsOnlyCodesForTheExactApplicationEndpoint() {
        assertTrue(isStarlitOAuthCallback("nuvio://auth?code=authorization-code"))
        assertTrue(isStarlitOAuthCallback("nuvio://auth/?code=authorization-code"))
        for (url in listOf("nuvio://auth#access_token=injected", "nuvio://auth?access_token=injected",
            "nuvio://auth?code=", "nuvio://auth?code=one&code=two", "nuvio://auth.evil?code=abc", "https://auth?code=abc",
            "nuvio://auth/path?code=abc", "nuvio://auth?code=abc#access_token=injected", "invalid")) {
            assertFalse(isStarlitOAuthCallback(url), url)
        }
    }
}

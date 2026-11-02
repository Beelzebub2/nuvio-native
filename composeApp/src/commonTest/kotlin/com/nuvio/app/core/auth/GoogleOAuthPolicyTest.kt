package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleOAuthPolicyTest {
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
            "nuvio://auth?code=", "nuvio://auth.evil?code=abc", "https://auth?code=abc",
            "nuvio://auth/path?code=abc", "nuvio://auth?code=abc#access_token=injected", "invalid")) {
            assertFalse(isStarlitOAuthCallback(url), url)
        }
    }
}

package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertTrue

class IntroDbConfigTest {
    @Test
    fun introDbIsConfiguredWithoutLocalProperties() {
        // Builds without local.properties (CI installers) must still query IntroDB, or no
        // episode ever gets skip segments.
        assertTrue(IntroDbConfig.URL.isNotBlank(), "IntroDB URL must default to the public API")
        assertTrue(IntroDbConfig.URL.startsWith("https://"))
    }
}

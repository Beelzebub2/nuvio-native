package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTelevisionRuntimeTest {
    @Test
    fun onlyDedicatedTvPackagesEnableTelevisionMode() {
        assertTrue(isTelevisionAppPackage("com.starlit.tv"))
        assertTrue(isTelevisionAppPackage("com.starlit.tv.debug"))
        assertFalse(isTelevisionAppPackage("com.starlit.tv.other"))
        assertFalse(isTelevisionAppPackage("com.nuvio.app"))
        assertFalse(isTelevisionAppPackage("com.nuvio.app.debug"))
    }
}

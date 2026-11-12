package com.nuvio.app.core.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class WindowsDisplayAdaptersTest {
    @Test
    fun matchesWindowsWideDisplayDeviceStructure() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assertEquals(840, WindowsDisplayAdapters.DisplayDevice().size())
    }

    @Test
    fun decodesNullTerminatedAdapterNameWithoutExtraCharacters() {
        assertEquals(
            "NVIDIA GeForce RTX 4070",
            (" NVIDIA GeForce RTX 4070\u0000unrelated data").toCharArray().toNullTerminatedName(),
        )
        assertEquals("", charArrayOf('\u0000', 'X').toNullTerminatedName())
    }
}

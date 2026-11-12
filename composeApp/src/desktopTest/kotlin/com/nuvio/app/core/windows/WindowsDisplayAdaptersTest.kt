package com.nuvio.app.core.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WindowsDisplayAdaptersTest {
    @Test
    fun matchesWindowsWideDisplayDeviceStructure() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assertEquals(840, WindowsDisplayAdapters.DisplayDevice().size())
    }

    @Test
    fun enumeratesNativeWindowsDisplayAdapters() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val names = assertNotNull(WindowsDisplayAdapters.names(), "Native adapter enumeration failed")
        assertTrue(names.isNotEmpty(), "Windows must expose at least one display adapter")
        assertTrue(names.all(String::isNotBlank), "Display adapter names must not contain blank entries")
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

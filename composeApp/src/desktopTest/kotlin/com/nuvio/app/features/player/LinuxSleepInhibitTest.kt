package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxSleepInhibitTest {
    @Test
    fun theKeepAwakeCommandTerminatesWhenItsParentExits() {
        val command = linuxSystemdInhibitCommand(12345L)
        assertEquals("systemd-inhibit", command.first())
        assertEquals("/bin/sh", command[5])
        assertEquals("-c", command[6])
        assertTrue(command[7].contains("kill -0"))
        assertTrue(command[7].contains("sleep 1"))
        assertEquals("12345", command.last())
    }

    @Test
    fun rejectsInvalidParentProcess() {
        assertFailsWith<IllegalArgumentException> { linuxSystemdInhibitCommand(0L) }
    }
}

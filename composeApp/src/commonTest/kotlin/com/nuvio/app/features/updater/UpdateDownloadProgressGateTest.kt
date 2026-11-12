package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloadProgressGateTest {
    @Test
    fun reportsMeaningfulPercentChangesAndCompletion() {
        val gate = UpdateDownloadProgressGate()
        assertTrue(gate.shouldReport(0L, 10_000L))
        (1L..99L).forEach { assertFalse(gate.shouldReport(it, 10_000L)) }
        assertTrue(gate.shouldReport(100L, 10_000L))
        (101L..199L).forEach { assertFalse(gate.shouldReport(it, 10_000L)) }
        assertTrue(gate.shouldReport(10_000L, 10_000L))
        assertFalse(gate.shouldReport(10_000L, 10_000L))
    }

    @Test
    fun unknownLengthDoesNotFloodStateUpdates() {
        val gate = UpdateDownloadProgressGate()
        assertTrue(gate.shouldReport(512L, null))
        assertFalse(gate.shouldReport(1024L, null))
        assertTrue(gate.shouldReport(1024L, 2048L))
        assertFalse(gate.shouldReport(1025L, 2048L))
        assertTrue(gate.shouldReport(2048L, 2048L))
    }
}

package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopP2pRecoverySignalsTest {
    private val wifi = setOf("wifi:192.0.2.1")
    private fun sample(time: Long, network: Set<String>? = wifi, wall: Long = time) = DesktopP2pEnvironment(wall, time, network)

    @Test fun aWallClockGapCatchesSleepWhenTheMonotonicClockPauses() {
        val signals = DesktopP2pRecoverySignals(sample(0))
        assertNull(signals.observe(sample(2_000, wall = 302_000)))
        assertEquals(DesktopP2pRecoveryReason.Resume, signals.observe(sample(4_000, wall = 304_000)))
        assertNull(signals.observe(sample(6_000, wall = 306_000)))
    }

    @Test fun unknownNetworkDoesNotPretendTheAdapterDisconnected() {
        val signals = DesktopP2pRecoverySignals(sample(0))
        assertNull(signals.observe(sample(2_000, null)))
        assertNull(signals.observe(sample(4_000)))
        assertNull(signals.observe(sample(6_000)))
    }

    @Test fun wakeUpStillRecoversIfInterfaceEnumerationIsUnavailable() {
        val signals = DesktopP2pRecoverySignals(sample(0, null))
        assertNull(signals.observe(sample(2_000, null, wall = 302_000)))
        assertEquals(DesktopP2pRecoveryReason.Resume, signals.observe(sample(4_000, null, wall = 304_000)))
    }

    @Test fun changingInterfacesWaitsForTwoStableSamples() {
        val signals = DesktopP2pRecoverySignals(sample(0))
        assertNull(signals.observe(sample(2_000, emptySet())))
        assertNull(signals.observe(sample(4_000, setOf("vpn:192.0.2.2"))))
        assertNull(signals.observe(sample(6_000, setOf("ethernet:192.0.2.3"))))
        assertNull(signals.observe(sample(8_000, setOf("ethernet:192.0.2.3"))))
        assertEquals(DesktopP2pRecoveryReason.NetworkChange,
            signals.observe(sample(10_000, setOf("ethernet:192.0.2.3"))))
    }
}

package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveRemoteDesktopTest {
    @Test
    fun activeBluetoothDeviceMatchesOnlyItsVerifiedEndpoint() {
        val active = RemoteDesktop("02:12:34:56:78:9a", 0, "Office PC", bluetooth = true)

        assertTrue(isActiveRemoteDesktop(true, active, "02:12:34:56:78:9A", bluetooth = true))
        assertFalse(isActiveRemoteDesktop(false, active, "02:12:34:56:78:9A", bluetooth = true))
        assertFalse(isActiveRemoteDesktop(true, null, "02:12:34:56:78:9A", bluetooth = true))
        assertFalse(isActiveRemoteDesktop(true, active, "02:12:34:56:78:9B", bluetooth = true))
        assertFalse(isActiveRemoteDesktop(true, active, "02:12:34:56:78:9A", bluetooth = false))
    }

    @Test
    fun activeLanPcMatchesIpButNotOtherMachinesOrBluetooth() {
        val active = RemoteDesktop("192.168.1.50", 57816, "Office PC")

        assertTrue(isActiveRemoteDesktop(true, active, "192.168.1.50", bluetooth = false))
        assertFalse(isActiveRemoteDesktop(true, active, "192.168.1.51", bluetooth = false))
        assertFalse(isActiveRemoteDesktop(true, active, "192.168.1.50", bluetooth = true))
    }
}
